package com.example.personality.ai;

import com.example.personality.config.AiProperties;
import com.example.personality.dto.SessionResultResponse;
import com.example.personality.exception.AiServiceException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 真实的 AI 反馈生成：调用 DeepSeek 的 OpenAI 兼容接口。
 *
 * <p><b>只在 {@code app.ai.enabled=true} 时装配。</b>
 * 未启用时容器里是 {@link StubAiReportGenerator}（返回 501）。
 * 两个实现用同一个配置项的正反条件互斥，保证任何时候都<b>恰好有一个</b>
 * {@code AiReportGenerator} Bean 存在——不会出现"找不到 Bean"或
 * "两个候选不知道该注入哪个"。
 *
 * <p>DeepSeek 的接口和 OpenAI 完全兼容（路径 {@code /chat/completions}、
 * 同样的 {@code messages} 结构、同样的 {@code choices[0].message.content}），
 * 所以换成通义、智谱、Kimi 多数只要改 {@code app.ai.base-url} 和 {@code model}，
 * 这个类一行都不用动。这也是为什么配置项要抽成 {@link AiProperties}。
 */
@Component
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true")
public class DeepSeekAiReportGenerator implements AiReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekAiReportGenerator.class);

    private final AiProperties properties;
    private final AiPromptBuilder promptBuilder;
    private final RestClient restClient;

    public DeepSeekAiReportGenerator(AiProperties properties, AiPromptBuilder promptBuilder) {
        this.properties = properties;
        this.promptBuilder = promptBuilder;

        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            // 提前失败，且信息要足够明确——否则用户会拿到一个语焉不详的 401，
            // 然后花时间怀疑是自己的网络问题。
            throw new IllegalStateException(
                    "app.ai.enabled=true 但未配置 API Key。"
                            + "请设置环境变量 DEEPSEEK_API_KEY 后重启应用。");
        }

        // ⚠️ 超时是必须设的。
        // 不设的话，默认是"无限等待"——上游卡住时，请求线程会一直被占着，
        // 并发几十个就把 Tomcat 的线程池耗尽了，整个服务失去响应。
        // 外部依赖调用永远要有超时。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = properties.getTimeout();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);

        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
                .build();
    }

    @Override
    public String generateReport(SessionResultResponse result) {
        Map<String, Object> body = Map.of(
                "model", properties.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", promptBuilder.systemPrompt()),
                        Map.of("role", "user", "content", promptBuilder.userPrompt(result))
                ),
                "temperature", properties.getTemperature(),
                "max_tokens", properties.getMaxTokens(),
                "stream", false
        );

        long startedAt = System.currentTimeMillis();
        ChatCompletionResponse response;
        try {
            response = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(ChatCompletionResponse.class);
        } catch (RestClientResponseException e) {
            // 上游返回了 4xx / 5xx。这里把状态码和响应体一起记下来——
            // 401 是 Key 错了，402 是余额不足，429 是限流，400 多半是模型名写错了。
            // 不记响应体的话，排查时只能靠猜。
            log.error("DeepSeek 返回错误 status={} body={}",
                    e.getStatusCode(), abbreviate(e.getResponseBodyAsString()));
            throw new AiServiceException(
                    "AI 服务返回错误（HTTP " + e.getStatusCode().value() + "），请稍后重试", e);
        } catch (ResourceAccessException e) {
            // 连不上或超时
            log.error("DeepSeek 连接失败：{}", e.getMessage());
            throw new AiServiceException("连接 AI 服务超时或失败，请稍后重试", e);
        }

        String content = extractContent(response);
        log.info("DeepSeek 生成成功 model={} 耗时={}ms 字数={}",
                properties.getModel(), System.currentTimeMillis() - startedAt, content.length());
        return content;
    }

    /**
     * 从响应里取正文。
     *
     * <p>这个方法看着啰嗦，但每一层判空都对应一种真实的失败：
     * <ul>
     *   <li>{@code response} 为 null —— 响应体是空的（204，或者上游返回了非 JSON）</li>
     *   <li>{@code choices} 为空 —— 触发了内容审核，或模型拒答，这时通常伴随 finish_reason</li>
     *   <li>{@code content} 为空白 —— 达到了 max_tokens 上限，正文还没开始就被截断</li>
     * </ul>
     *
     * <p>把这些情况"翻译"成明确的中文报错，比让一个 NullPointerException
     * 冒到 GlobalExceptionHandler 里强得多——后者用户只会看到「服务器内部错误」，
     * 完全无从下手。
     */
    private String extractContent(ChatCompletionResponse response) {
        if (response == null) {
            throw new AiServiceException("AI 服务返回了空响应");
        }
        if (response.choices() == null || response.choices().isEmpty()) {
            throw new AiServiceException("AI 服务没有返回任何内容（可能触发了内容审核）");
        }
        ChatMessage message = response.choices().get(0).message();
        if (message == null || message.content() == null || message.content().isBlank()) {
            throw new AiServiceException("AI 返回的内容为空（可能达到了长度上限）");
        }
        return message.content().trim();
    }

    private static String abbreviate(String text) {
        if (text == null) return "";
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    @Override
    public String providerName() {
        return "deepseek:" + properties.getModel();
    }

    // ==========================================================
    // 响应结构。只需要声明我们关心的字段。
    // ==========================================================

    /**
     * {@code @JsonIgnoreProperties(ignoreUnknown = true)} 是必须的。
     *
     * <p>大模型 API 的响应里有一大堆我们不需要的字段（id、created、usage、
     * system_fingerprint...）。不加这个注解，Jackson 遇到未知字段会直接抛异常，
     * 而且厂商每次加新字段都可能把你的服务打挂。
     *
     * <p>这些用 record 来承载：Jackson 能直接反序列化进 record 的构造器，
     * 不用写任何 setter 或 getter。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatCompletionResponse(List<Choice> choices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(ChatMessage message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatMessage(String content) {
    }
}
