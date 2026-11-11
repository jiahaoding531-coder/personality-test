package com.example.personality.ai;

import com.example.personality.entity.RecommendationFeedback;
import com.example.personality.exception.AiServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 调用模型并严格校验自然语言推荐反馈。
 *
 * <p>模型是翻译器，不是数值决策者：operation 的通用语法交给
 * {@link DeepSeekTextIntentGenerator#parseOperations(JsonNode)} 统一解析，
 * 本类只追加反馈场景特有的“这个数字是否被服务端或用户原话授权”检查。</p>
 */
@Component
public class DeepSeekTextFeedbackGenerator implements TextFeedbackGenerator {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekTextFeedbackGenerator.class);
    // 显式拒绝一个合法对象之后的第二段 JSON 或垃圾，不能依赖库版本的默认值。
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final Pattern DISTANCE = Pattern.compile(
            "(?i)(\\d+(?:\\.\\d+)?)\\s*(公里|千米|km|米|m)");
    private static final Pattern BUDGET = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(元|块|人民币)");
    private static final Pattern DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(小时|分钟|分)");
    private static final Pattern PURE_ALTERNATIVE = Pattern.compile(
            "^(?:那就|那|请|帮我|麻烦)?(?:换(?:一个|一家|个地方|个|一批|一下)?|来(?:一个|一批)|"
                    + "再来(?:一个|一批)|另一个|别的)(?:吧|呀|啊)?$");

    private final DeepSeekChatClient chatClient;
    private final TextFeedbackPromptBuilder promptBuilder;

    public DeepSeekTextFeedbackGenerator(DeepSeekChatClient chatClient,
                                         TextFeedbackPromptBuilder promptBuilder) {
        this.chatClient = chatClient;
        this.promptBuilder = promptBuilder;
    }

    @Override
    public TextFeedbackResult interpret(AiCredentials credentials, FeedbackPromptContext context) {
        if (context == null || context.rawText() == null || context.rawText().isBlank()) {
            return TextFeedbackResult.empty();
        }
        String content = chatClient.complete(credentials, promptBuilder.systemPrompt(),
                promptBuilder.userPrompt(context), true);
        TextFeedbackResult result = parse(content, context);
        // 仅记录 provider 与统计值；原话、模型原文和 key 都不写日志。
        log.info("推荐反馈解析完成 endpoint={} 操作数={} 信号数={} 未识别={}",
                credentials.providerName(), result.operations().size(), result.signals().size(),
                result.unrecognized().size());
        return result;
    }

    /**
     * 将模型 JSON 转为受服务端约束的反馈结果。
     *
     * <p>整个 JSON 无法读取时才抛 502；数组里某一项不合法时只丢弃那一项，
     * 这样一条编造的距离不会连带丢掉同句中有效的“想安静”。</p>
     */
    static TextFeedbackResult parse(String content, FeedbackPromptContext context) {
        JsonNode root;
        String json = stripCodeFence(content);
        try {
            root = JSON.readTree(json);
        } catch (RuntimeException e) {
            // 模型输出也是不可信输入，不能因为排错又把它写进日志。
            log.warn("推荐反馈返回的不是合法 JSON");
            throw new AiServiceException("AI 返回的内容不是合法的 JSON，无法解析", e);
        }
        if (root == null || !root.isObject()) {
            log.warn("推荐反馈返回的 JSON 根节点不是对象");
            throw new AiServiceException("AI 返回的 JSON 根节点必须是对象");
        }

        RecommendationFeedback.Reaction reaction = parseEnum(
                text(root, "reaction"), RecommendationFeedback.Reaction.values());
        Set<TravelFeedbackSignal> signals = parseSignals(root.path("signals"));
        List<TravelIntentOperation> operations = parseOperations(root.path("operations"), context);
        if (isPureAlternativeRequest(context)) {
            // 原话仅仅是在要“换一批”时，服务端确定性地刷新候选，
            // 不依赖模型是否正确返回 signal，更不允许它夹带会话条件修改。
            signals.add(TravelFeedbackSignal.REQUEST_ALTERNATIVE);
            operations = List.of();
        }
        TravelFeedbackKind kind = parseEnum(text(root, "kind"), TravelFeedbackKind.values());
        if (kind == null) {
            kind = deriveKind(reaction, operations);
        }

        boolean hasAlternative = signals.contains(TravelFeedbackSignal.REQUEST_ALTERNATIVE);
        boolean shouldRecommend = !operations.isEmpty() || hasAlternative;
        return new TextFeedbackResult(kind, reaction, Set.copyOf(signals), List.copyOf(operations),
                parseStringList(root.path("unrecognized")), text(root, "summary"),
                shouldRecommend, hasAlternative);
    }

    private static List<TravelIntentOperation> parseOperations(JsonNode node,
                                                                 FeedbackPromptContext context) {
        List<TravelIntentOperation> accepted = new ArrayList<>();
        if (!node.isArray()) {
            return accepted;
        }
        for (JsonNode item : node) {
            // 通用解析器负责 operation、状态、维度和约束 key 的白名单及结构检查。
            JsonNode singleton = JSON.createArrayNode().add(item);
            List<TravelIntentOperation> parsed = DeepSeekTextIntentGenerator.parseOperations(singleton);
            if (parsed.isEmpty()) {
                continue;
            }
            TravelIntentOperation operation = parsed.get(0);
            if (isAuthorizedConstraint(operation, item.path("value"), context)) {
                accepted.add(operation);
            }
        }
        return accepted;
    }

    private static boolean isAuthorizedConstraint(TravelIntentOperation operation,
                                                   JsonNode rawValue,
                                                   FeedbackPromptContext context) {
        if (operation.op() != TravelIntentOperation.Type.SET_CONSTRAINT
                || operation.numberValue() == null) {
            return true;
        }
        Double rawNumber = asNumber(rawValue);
        if (rawNumber == null || !sameNumber(rawNumber, operation.numberValue())) {
            return false;
        }
        String rawText = context == null ? "" : safe(context.rawText());
        return switch (operation.constraintKey()) {
            case MAX_DISTANCE_METERS -> sameNumber(rawNumber, context.suggestedCloserDistanceMeters())
                    || userExplicitlySaysDistance(rawText, rawNumber);
            case BUDGET_MAX -> sameNumber(rawNumber, context.suggestedBudgetMax())
                    || userExplicitlySaysBudget(rawText, rawNumber);
            case DURATION_MINUTES -> userExplicitlySaysDuration(rawText, rawNumber);
        };
    }

    private static boolean userExplicitlySaysDistance(String rawText, double value) {
        Matcher matcher = DISTANCE.matcher(rawText);
        while (matcher.find()) {
            double number = Double.parseDouble(matcher.group(1));
            String unit = matcher.group(2).toLowerCase(Locale.ROOT);
            double meters = unit.equals("公里") || unit.equals("千米") || unit.equals("km")
                    ? number * 1000 : number;
            if (sameNumber(value, meters)) {
                return true;
            }
        }
        return false;
    }

    private static boolean userExplicitlySaysBudget(String rawText, double value) {
        return containsExplicitValue(BUDGET, rawText, value, 1.0);
    }

    private static boolean userExplicitlySaysDuration(String rawText, double value) {
        Matcher matcher = DURATION.matcher(rawText);
        while (matcher.find()) {
            double number = Double.parseDouble(matcher.group(1));
            double minutes = matcher.group(2).equals("小时") ? number * 60 : number;
            if (sameNumber(value, minutes)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsExplicitValue(Pattern pattern, String rawText, double value,
                                                 double multiplier) {
        Matcher matcher = pattern.matcher(rawText);
        while (matcher.find()) {
            if (sameNumber(value, Double.parseDouble(matcher.group(1)) * multiplier)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 只识别整句都在请求替换的保守表达；“这家太远，换一个吧”不在此列，
     * 因为它确实还携带了可用于下一轮推荐的额外语义。
     */
    private static boolean isPureAlternativeRequest(FeedbackPromptContext context) {
        if (context == null) {
            return false;
        }
        String normalized = safe(context.rawText()).replaceAll("[\\s，。！？!?]", "");
        return PURE_ALTERNATIVE.matcher(normalized).matches();
    }

    private static Set<TravelFeedbackSignal> parseSignals(JsonNode node) {
        Set<TravelFeedbackSignal> signals = new LinkedHashSet<>();
        if (!node.isArray()) {
            return signals;
        }
        for (JsonNode item : node) {
            if (item.isString()) {
                TravelFeedbackSignal signal = parseEnum(item.asString(""), TravelFeedbackSignal.values());
                if (signal != null) {
                    signals.add(signal);
                }
            }
        }
        return signals;
    }

    private static TravelFeedbackKind deriveKind(RecommendationFeedback.Reaction reaction,
                                                  List<TravelIntentOperation> operations) {
        if (reaction != null && !operations.isEmpty()) {
            return TravelFeedbackKind.MIXED;
        }
        return reaction != null ? TravelFeedbackKind.PLACE_OPINION
                : TravelFeedbackKind.SESSION_ADJUSTMENT;
    }

    private static <E extends Enum<E>> E parseEnum(String raw, E[] values) {
        String normalized = safe(raw).toUpperCase(Locale.ROOT);
        for (E value : values) {
            if (value.name().equals(normalized)) {
                return value;
            }
        }
        return null;
    }

    private static List<String> parseStringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (!node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            if (item.isString() && !safe(item.asString("")).isEmpty()) {
                values.add(item.asString("").trim());
            }
        }
        return values;
    }

    private static Double asNumber(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isString()) {
            try {
                return Double.parseDouble(node.asString("").trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean sameNumber(Double left, Integer right) {
        return right != null && sameNumber(left, right.doubleValue());
    }

    private static boolean sameNumber(double left, double right) {
        return Math.abs(left - right) < 1e-9;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.isArray() ? "" : value.asString("");
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String stripCodeFence(String content) {
        String trimmed = safe(content);
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

}
