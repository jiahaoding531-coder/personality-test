package com.example.personality.ai;

import com.example.personality.exception.AiServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 真实的推荐理由生成：让 DeepSeek 把打分依据写成人话。
 *
 * <p><b>只在 {@code app.ai.enabled=true} 时装配。</b>
 * 未启用时容器里是 {@link StubTravelReasonGenerator}（返回 501）。
 * 两个实现用同一个配置项的正反条件互斥，保证容器里<b>恰好有一个</b>
 * {@code TravelReasonGenerator}。
 *
 * <h2>和其他实现共用同一个 HTTP 客户端</h2>
 *
 * <p>{@link DeepSeekChatClient} 里装的是超时、错误处理、响应解析这些
 * "踩过一次才知道要写"的东西。这个类只管两件事：拼提示词、
 * 把模型返回的 JSON 解析成结构化的理由。
 */
@Component
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true")
public class DeepSeekTravelReasonGenerator implements TravelReasonGenerator {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekTravelReasonGenerator.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DeepSeekChatClient chatClient;
    private final TravelReasonPromptBuilder promptBuilder;

    public DeepSeekTravelReasonGenerator(DeepSeekChatClient chatClient,
                                         TravelReasonPromptBuilder promptBuilder) {
        this.chatClient = chatClient;
        this.promptBuilder = promptBuilder;
    }

    @Override
    public List<RankedReason> generateReasons(TravelReasonInput input) {
        // jsonMode=true：要求上游返回一个 JSON 对象。
        // 提示词里必须有 "json" 字样，否则多数厂商会直接拒掉这次请求。
        String content = chatClient.complete(
                promptBuilder.systemPrompt(),
                promptBuilder.userPrompt(input),
                true);

        List<RankedReason> reasons = parse(content, input.places().size());
        log.info("推荐理由生成成功 条数={} 地点数={}", reasons.size(), input.places().size());
        return reasons;
    }

    /**
     * 把模型返回的 JSON 解析成理由列表。
     *
     * <p><b>刻意写成 static 包级可见的纯函数</b>，不碰网络也不碰 Spring——
     * 这样解析逻辑可以毫秒级单测（见 {@code DeepSeekTravelReasonGeneratorTest}），
     * 不需要 key、不花钱、不受网络影响。
     *
     * <p>大模型的输出是不受我们控制的，所以这里对格式的各种意外都要有准备：
     * <ul>
     *   <li>外面裹了一层 {@code ```json} 代码块（即使开了 json 模式也可能发生）</li>
     *   <li>{@code rank} 给成字符串 {@code "1"} 而不是数字 {@code 1}</li>
     *   <li>多写或少写了某几个名次</li>
     * </ul>
     *
     * <p>容错的原则是<b>坏的那条丢掉，好的那些留下</b>；但<b>一条都没解析出来
     * 就算失败</b>——那说明这次输出的格式完全不对，返回空列表会让上层
     * 误以为"生成成功了只是没内容"，从而把空结果缓存下来。
     *
     * @param expectedCount 预期几个地点。只用来记日志，不用来强制校验——
     *                      少一条也不该让整批作废
     */
    static List<RankedReason> parse(String content, int expectedCount) {
        String json = stripCodeFence(content);

        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (RuntimeException e) {
            // 不把原始输出打进日志的正文里——它可能有几百字，而且模型偶尔会
            // 把用户数据复述进去。截断一下够定位问题了。
            log.warn("大模型返回的不是合法 JSON，前 200 字：{}", abbreviate(json));
            throw new AiServiceException("AI 返回的内容不是合法的 JSON，无法解析成推荐理由", e);
        }

        JsonNode array = root.path("reasons");
        if (!array.isArray()) {
            log.warn("大模型返回的 JSON 里没有 reasons 数组：{}", abbreviate(json));
            throw new AiServiceException("AI 返回的 JSON 缺少 reasons 字段");
        }

        List<RankedReason> reasons = new ArrayList<>(array.size());
        for (JsonNode node : array) {
            int rank = readRank(node.path("rank"));
            String reason = readText(node, "reason");
            if (rank >= 1 && !reason.isBlank()) {
                reasons.add(new RankedReason(rank, reason.trim()));
            }
        }

        if (reasons.isEmpty()) {
            throw new AiServiceException("AI 没有返回任何可用的推荐理由");
        }
        if (reasons.size() != expectedCount) {
            // 只记日志不报错：能拿到几条就先给用户几条。
            // 上层会发现"还有地点没有理由"，下次请求会重新生成。
            log.warn("理由条数和地点数对不上：拿到 {} 条，预期 {} 条", reasons.size(), expectedCount);
        }
        return reasons;
    }

    /**
     * 去掉可能裹在外面的 {@code ```json ... ```}。
     *
     * <p>开了 json 模式理论上不该出现，但"理论上"这三个字在大模型身上
     * 一向不太可靠。剥一层壳的成本是几行代码，而收益是这类失败
     * 从"整批作废"变成"照常可用"。
     */
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

    /** 名次可能被写成数字 1，也可能被写成字符串 "1"。 */
    private static int readRank(JsonNode node) {
        if (node.isNumber()) {
            return node.asInt();
        }
        String text = node.isMissingNode() || node.isNull() ? "" : node.asString("");
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;   // 解析不出来就当无效，由调用方丢掉这一条
        }
    }

    private static String readText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.isArray()) {
            return "";
        }
        return value.asString("");
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    @Override
    public String providerName() {
        return chatClient.providerName();
    }
}
