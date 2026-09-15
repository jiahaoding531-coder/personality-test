package com.example.personality.ai;

import com.example.personality.config.AiProperties;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调大模型的公共 HTTP 层：拼请求、发出去、把各种失败翻译成人话。
 *
 * <h2>为什么要单独抽一层</h2>
 *
 * <p>这段代码原本整个写在 {@link DeepSeekAiReportGenerator} 里面。加旅行推荐理由
 * 的时候，有两条路：复制一份过去，或者抽出来共用。
 *
 * <p><b>复制一份是最省事的，也是最贵的。</b>这里面装的全是"踩过一次才知道要写"
 * 的东西：超时必须设、4xx/5xx 要把响应体记下来、响应里三层判空各对应一种真实失败、
 * 401/402/429 分别是什么意思。复制之后这些知识就有两份，将来改超时策略、
 * 加失败重试、换供应商，一定会漏掉其中一份——而且漏掉的那份不会报错，
 * 只会在某个凌晨表现得和另一份不一样。
 *
 * <h2>这一层只管"把话带到"，不管"说什么"</h2>
 *
 * <p>提示词怎么组织是各自的 {@code *PromptBuilder} 的事，业务上一句话代表什么
 * 是各自 Service 的事。这里只负责：给它两段文本，拿回一段文本；
 * 出任何问题都抛 {@link AiServiceException}（映射成 502）。
 */
@Component
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true")
public class DeepSeekChatClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekChatClient.class);

    private final AiProperties properties;
    private final RestClient restClient;

    public DeepSeekChatClient(AiProperties properties) {
        this.properties = properties;

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

    /**
     * 要一段纯文本的回答。
     *
     * @throws AiServiceException 上游报错、超时、或返回了看不懂的东西
     */
    public String complete(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, userPrompt, false);
    }

    /**
     * 要一段回答。
     *
     * @param jsonMode 为 true 时要求上游返回一个 JSON 对象
     *                 （OpenAI 兼容协议里的 {@code response_format}）。
     *                 <p>⚠️ <b>它只是"要求"，不是"保证"。</b>模型仍可能返回
     *                 带解释的文本或格式不对的 JSON，所以调用方拿到之后
     *                 <b>必须自己解析并处理失败</b>，不能假设它一定合法。
     *                 <p>另外：开了这个模式，提示词里必须出现 "json" 字样，
     *                 否则多数厂商会直接拒绝请求——这不是我们能替调用方决定的。
     */
    public String complete(String systemPrompt, String userPrompt, boolean jsonMode) {

        // LinkedHashMap 而不是 Map.of：要按条件增删字段，Map.of 是不可变的。
        // 用 LinkedHashMap 而不是 HashMap 只是为了日志里字段顺序稳定，便于比对。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        body.put("temperature", properties.getTemperature());
        body.put("max_tokens", properties.getMaxTokens());
        body.put("stream", false);
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }

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
            log.error("大模型返回错误 status={} body={}",
                    e.getStatusCode(), abbreviate(e.getResponseBodyAsString()));
            throw new AiServiceException(
                    "AI 服务返回错误（HTTP " + e.getStatusCode().value() + "），请稍后重试", e);
        } catch (ResourceAccessException e) {
            // 连不上或超时
            log.error("大模型连接失败：{}", e.getMessage());
            throw new AiServiceException("连接 AI 服务超时或失败，请稍后重试", e);
        }

        String content = extractContent(response);
        log.info("大模型调用成功 model={} jsonMode={} 耗时={}ms 字数={}",
                properties.getModel(), jsonMode,
                System.currentTimeMillis() - startedAt, content.length());
        return content;
    }

    /** 实现方标识，会随响应返回给前端，便于排查"这次到底是哪个模型生成的"。 */
    public String providerName() {
        return "deepseek:" + properties.getModel();
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
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
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
