package com.example.personality.ai;

import com.example.personality.domain.TravelDimension;
import com.example.personality.domain.TravelState;
import com.example.personality.exception.AiServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 让大模型把用户的口语翻译成结构化条件。
 *
 * <p>和其他实现共用 {@link DeepSeekChatClient}（HTTP、超时、错误处理都在那里），
 * 这个类只管两件事：拼提示词、把模型返回的 JSON 解析成 {@link TextIntentResult}。
 */
@Component
public class DeepSeekTextIntentGenerator implements TextIntentGenerator {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekTextIntentGenerator.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * 数值槽位的合法区间。
     *
     * <p>⚠️ 这三个区间**必须和 {@code RecommendationRequest} 上的
     * {@code @Min/@Max} 校验保持一致**。不一致的话，解析出来一个越界值、
     * 前端原样发出去，推荐请求会被 400 挡掉——而用户看到的只是"推荐失败了"，
     * 完全想不到是 AI 多说了半句。
     *
     * <p>所以解析时就夹进区间，而不是把越界值原样返回。
     */
    private static final int MIN_REMAINING_MINUTES = 15;
    private static final int MAX_REMAINING_MINUTES = 1440;
    private static final double MIN_DISTANCE_KM = 0.5;
    private static final double MAX_DISTANCE_KM = 50.0;
    private static final int MIN_TICKET_PRICE = 0;
    private static final int MAX_TICKET_PRICE = 1000;

    private final DeepSeekChatClient chatClient;
    private final TextIntentPromptBuilder promptBuilder;

    public DeepSeekTextIntentGenerator(DeepSeekChatClient chatClient,
                                       TextIntentPromptBuilder promptBuilder) {
        this.chatClient = chatClient;
        this.promptBuilder = promptBuilder;
    }

    @Override
    public TextIntentResult interpret(AiCredentials credentials, String text) {
        if (text == null || text.isBlank()) {
            return TextIntentResult.empty();
        }

        String content = chatClient.complete(
                credentials,
                promptBuilder.systemPrompt(),
                promptBuilder.userPrompt(text),
                true);

        TextIntentResult result = parse(content);
        log.info("文本解析完成 endpoint={} 状态数={} 倾向数={} 未识别={}",
                credentials.providerName(), result.states().size(),
                result.biases().size(), result.unrecognized().size());
        return result;
    }

    /**
     * 把模型返回的 JSON 解析成 {@link TextIntentResult}。
     *
     * <p><b>刻意写成 static 包级可见的纯函数</b>，不碰网络也不碰 Spring——
     * 可以毫秒级单测，不需要 key、不花钱。
     *
     * <h2>容错原则：坏的那条丢掉，好的留下</h2>
     *
     * <p>模型是不受控的，它会编出不存在的状态名、把数字写成字符串、
     * 给一个超出范围的分钟数。**任何一条脏数据都不该让整个解析失败**——
     * 用户说了五件事，能听懂三件就先给三件。
     *
     * <p>⚠️ 唯一的例外是**整个 JSON 都坏了**：那时抛 502，
     * 而不是返回空结果。因为空结果会被前端当成"这句我听不懂"，
     * 而实际上是我们没解析出来——这两件事该给用户看不同的话。
     */
    static TextIntentResult parse(String content) {
        String json = stripCodeFence(content);

        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (RuntimeException e) {
            log.warn("文本解析返回的不是合法 JSON，前 200 字：{}", abbreviate(json));
            throw new AiServiceException("AI 返回的内容不是合法的 JSON，无法解析", e);
        }

        return new TextIntentResult(
                parseStates(root.path("states")),
                parseBiases(root.path("biases")),
                parseClampedInt(root.path("remainingMinutes"),
                        MIN_REMAINING_MINUTES, MAX_REMAINING_MINUTES),
                parseClampedDouble(root.path("maxDistanceKm"),
                        MIN_DISTANCE_KM, MAX_DISTANCE_KM),
                parseClampedInt(root.path("maxTicketPrice"),
                        MIN_TICKET_PRICE, MAX_TICKET_PRICE),
                parseStringList(root.path("unrecognized")),
                text(root, "summary"),
                parseOperations(root.path("operations")));
    }

    private static List<TravelIntentOperation> parseOperations(JsonNode node) {
        List<TravelIntentOperation> operations = new ArrayList<>();
        if (!node.isArray()) {
            return operations;
        }
        for (JsonNode item : node) {
            String opName = text(item, "op").trim().toUpperCase(java.util.Locale.ROOT);
            TravelIntentOperation.Type op;
            try {
                op = TravelIntentOperation.Type.valueOf(opName);
            } catch (IllegalArgumentException ignored) {
                continue;
            }

            TravelIntentOperation parsed = switch (op) {
                case ADD_INTENT, REMOVE_INTENT -> parseStateOperation(item, op, true);
                case ADD_PREFERENCE, REMOVE_PREFERENCE -> parseStateOperation(item, op, false);
                case REPLACE_INTENTS -> parseReplaceIntentsOperation(item);
                case SET_CONSTRAINT -> parseConstraintOperation(item);
                case MERGE_BIASES -> new TravelIntentOperation(
                        op, null, List.of(), null, null,
                        parseBiases(item.path("values")));
                case CLEAR_TRAVEL_INTENT -> new TravelIntentOperation(
                        op, null, List.of(), null, null, Map.of());
            };
            if (parsed != null) {
                operations.add(parsed);
            }
        }
        return operations;
    }

    private static TravelIntentOperation parseStateOperation(
            JsonNode item, TravelIntentOperation.Type op, boolean wantsIntent) {
        TravelState state = parseOperationState(item.path("value"));
        if (state == null || isCoreIntent(state) != wantsIntent) {
            return null;
        }
        return new TravelIntentOperation(op, state, List.of(), null, null, Map.of());
    }

    private static TravelIntentOperation parseReplaceIntentsOperation(JsonNode item) {
        List<TravelState> states = parseOperationStates(item.path("values"), true);
        if (states.isEmpty()) {
            return null;
        }
        return new TravelIntentOperation(
                TravelIntentOperation.Type.REPLACE_INTENTS,
                null, states, null, null, Map.of());
    }

    private static TravelIntentOperation parseConstraintOperation(JsonNode item) {
        String rawKey = text(item, "key").trim();
        TravelIntentOperation.ConstraintKey key = switch (rawKey) {
            case "durationMinutes" -> TravelIntentOperation.ConstraintKey.DURATION_MINUTES;
            case "maxDistanceMeters" -> TravelIntentOperation.ConstraintKey.MAX_DISTANCE_METERS;
            case "budgetMax" -> TravelIntentOperation.ConstraintKey.BUDGET_MAX;
            default -> null;
        };
        if (key == null) {
            return null;
        }

        Double rawValue = asDouble(item.path("value"));
        if (rawValue == null) {
            return null;
        }
        double value = switch (key) {
            case DURATION_MINUTES -> Math.max(MIN_REMAINING_MINUTES,
                    Math.min(MAX_REMAINING_MINUTES, Math.round(rawValue)));
            case MAX_DISTANCE_METERS -> Math.max(MIN_DISTANCE_KM * 1000,
                    Math.min(MAX_DISTANCE_KM * 1000, rawValue));
            case BUDGET_MAX -> Math.max(MIN_TICKET_PRICE,
                    Math.min(MAX_TICKET_PRICE, Math.round(rawValue)));
        };
        return new TravelIntentOperation(
                TravelIntentOperation.Type.SET_CONSTRAINT,
                null, List.of(), key, value, Map.of());
    }

    private static List<TravelState> parseOperationStates(JsonNode node, boolean wantsIntent) {
        List<TravelState> states = new ArrayList<>();
        if (!node.isArray()) {
            return states;
        }
        for (JsonNode item : node) {
            TravelState state = parseOperationState(item);
            if (state != null && isCoreIntent(state) == wantsIntent && !states.contains(state)) {
                states.add(state);
            }
        }
        return states;
    }

    private static TravelState parseOperationState(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        String normalized = node.asString("").trim().toUpperCase(java.util.Locale.ROOT);
        for (TravelState state : TravelState.values()) {
            if (state.name().equals(normalized)) {
                return state;
            }
        }
        return null;
    }

    private static boolean isCoreIntent(TravelState state) {
        return state == TravelState.HUNGRY || state == TravelState.WANT_WALK;
    }

    /**
     * 状态名：只认词表里有的。
     *
     * <p>⚠️ 认不出的直接丢掉，**不"退回默认状态"**。模型编一个 {@code SLEEPY} 出来，
     * 当成 {@code TIRED} 处理的话，用户看到的是"系统理解成你累了"——
     * 而他从没说过累。丢掉的代价只是少一个条件，编一个的代价是**改变了他没要求的事**。
     */
    private static Set<TravelState> parseStates(JsonNode node) {
        Set<TravelState> states = new LinkedHashSet<>();
        if (!node.isArray()) {
            return states;
        }
        for (JsonNode item : node) {
            String name = item.isString() ? item.asString("") : "";
            String normalized = name.trim().toUpperCase(java.util.Locale.ROOT);
            for (TravelState state : TravelState.values()) {
                if (state.name().equals(normalized)) {
                    states.add(state);
                }
            }
        }
        return states;
    }

    /**
     * 原始维度倾向：维度名必须在词表里，数值夹到 [-1, 1]。
     *
     * <p>这里的夹取是**第一道**，引擎里还有一道（按维度求和之后再夹）。
     * 两道都要有：这道防的是"单条越界"，那道防的是"多条相加之后越界"——
     * 而后者才是真正会算出负分、撞数据库 CHECK 的那条路。
     */
    private static Map<TravelDimension, Double> parseBiases(JsonNode node) {
        Map<TravelDimension, Double> biases = new EnumMap<>(TravelDimension.class);
        if (!node.isObject()) {
            return biases;
        }
        node.properties().forEach(entry -> {
            TravelDimension.fromName(entry.getKey()).ifPresent(dimension -> {
                Double value = asDouble(entry.getValue());
                if (value != null) {
                    biases.put(dimension, Math.max(-1.0, Math.min(1.0, value)));
                }
            });
        });
        return biases;
    }

    private static Integer parseClampedInt(JsonNode node, int min, int max) {
        Double value = asDouble(node);
        if (value == null) {
            return null;
        }
        return (int) Math.max(min, Math.min(max, Math.round(value)));
    }

    private static Double parseClampedDouble(JsonNode node, double min, double max) {
        Double value = asDouble(node);
        if (value == null) {
            return null;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static List<String> parseStringList(JsonNode node) {
        List<String> items = new ArrayList<>();
        if (!node.isArray()) {
            return items;
        }
        for (JsonNode item : node) {
            if (item.isString()) {
                String value = item.asString("").trim();
                if (!value.isEmpty()) {
                    items.add(value);
                }
            }
        }
        return items;
    }

    /** 取一个数字。字符串形式的数字（{@code "60"}）也认——模型经常这么写。 */
    private static Double asDouble(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isString()) {
            try {
                return Double.parseDouble(node.asString("").trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.isArray()) {
            return "";
        }
        return value.asString("");
    }

    /** 剥掉可能裹在外面的 {@code ```json}。开了 json 模式也可能出现。 */
    private static String stripCodeFence(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        if (firstNewline < 0) {
            return trimmed;
        }
        String body = trimmed.substring(firstNewline + 1);
        int closing = body.lastIndexOf("```");
        return closing >= 0 ? body.substring(0, closing).trim() : body.trim();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
