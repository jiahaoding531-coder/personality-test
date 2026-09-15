package com.example.personality.ai;

import com.example.personality.config.AiProperties;
import com.example.personality.exception.AiServiceException;
import com.example.personality.exception.InvalidAiCredentialsException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>OpenAI 兼容协议，所以 DeepSeek / 通义 / 智谱 / Kimi 共用这一个客户端——
 * 路径、请求体、响应结构都一模一样。
 *
 * <h2>⚠️ 凭据每次调用现传，不放在这个对象里</h2>
 *
 * <p>接入"访客自带 key"之前，key 是设在 {@code RestClient} 的**默认头**上的，
 * 这个类也就跟着变成"要么配了 key、要么根本不装配"的条件 Bean。
 *
 * <p>现在不行了：同一时刻可能有访客 A 用他自己的 DeepSeek key、
 * 访客 B 用通义、访客 C 什么都没带（走服务端那把）。<b>客户端只能有一个，
 * 凭据必须随每次调用传进来。</b>
 *
 * <p>好处不只是"能支持多用户"，还有一条更重要的：
 * <b>凭据的生命周期就只有那一次调用</b>。不驻留在任何单例对象的字段里，
 * 也就不会被内存转储、不会因为某个日志语句被顺带打出去。
 *
 * <p>另一个自然的推论：<b>不能按 key 缓存客户端</b>（那等于把别人的凭证
 * 长期留在内存里）。所以这里的 {@code RestClient} <b>只配超时</b>，
 * 不配 baseUrl、不配默认 Authorization——目标地址和凭证都由单次调用带上。
 */
@Component
public class DeepSeekChatClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekChatClient.class);

    /** OpenAI 兼容协议的补全路径。四家厂商都一样。 */
    private static final String COMPLETIONS_PATH = "/chat/completions";

    private final RestClient restClient;

    /**
     * 生成参数（温度、上限）来自服务端配置。
     *
     * <p>和凭据不同，这些**不是**每次调用现传的：它们描述的是
     * "我们想让模型怎么写"，属于这个应用的配置，跟用谁的 key 无关。
     */
    private final double temperature;
    private final int maxTokens;

    public DeepSeekChatClient(AiProperties properties) {
        this.temperature = properties.getTemperature();
        this.maxTokens = properties.getMaxTokens();

        // ⚠️ 这里**不再检查 key**。以前"启用却没配 key"要启动就炸，
        //    因为那时 AI 能不能用是启动时的事。现在能不能用取决于**每次请求带没带 key**，
        //    单例客户端无从判断，也不该判断——判断在 AiCredentialsResolver 里。
        //
        // 超时仍然是必须设的：不设默认就是"无限等待"，上游卡住会把 Tomcat 的
        // 请求线程一个个占满。这条和以前一样。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = properties.getTimeout();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    /**
     * 要一段纯文本的回答。
     *
     * @throws AiServiceException           上游报错、超时、或返回了看不懂的东西（502）
     * @throws InvalidAiCredentialsException <b>访客自带的</b> key 被上游拒绝（400）
     */
    public String complete(AiCredentials credentials, String systemPrompt, String userPrompt) {
        return complete(credentials, systemPrompt, userPrompt, false);
    }

    /**
     * 要一段回答。
     *
     * @param jsonMode 为 true 时要求上游返回一个 JSON 对象
     *                 （OpenAI 兼容协议里的 {@code response_format}）。
     *                 <p>⚠️ <b>它只是"要求"，不是"保证"。</b>模型仍可能返回
     *                 带解释的文本或格式不对的 JSON，所以调用方拿到之后
     *                 <b>必须自己解析并处理失败</b>。
     *                 <p>另外：开了这个模式，提示词里必须出现 "json" 字样，
     *                 否则多数厂商会直接拒绝——这不是我们能替调用方决定的。
     */
    public String complete(AiCredentials credentials, String systemPrompt,
                           String userPrompt, boolean jsonMode) {

        // LinkedHashMap 而不是 Map.of：要按条件增删字段，Map.of 是不可变的。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", credentials.model());
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }

        long startedAt = System.currentTimeMillis();
        ChatCompletionResponse response;
        try {
            response = restClient.post()
                    .uri(completionsUri(credentials))
                    // ⚠️ Authorization 设在**单次请求**上，不是 RestClient 的默认头。
                    //    这样它就只活在这一个调用里，不会被后续请求复用、也不会
                    //    跟着客户端对象一直留在内存中。
                    .header("Authorization", "Bearer " + credentials.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(ChatCompletionResponse.class);
        } catch (RestClientResponseException e) {
            throw translateUpstreamError(credentials, e);
        } catch (ResourceAccessException e) {
            // 连不上或超时
            log.error("大模型连接失败 endpoint={} {}", credentials.providerName(), e.getMessage());
            throw new AiServiceException("连接 AI 服务超时或失败，请稍后重试", e);
        }

        String content = extractContent(response);
        log.info("大模型调用成功 endpoint={} jsonMode={} 耗时={}ms 字数={}",
                credentials.providerName(), jsonMode,
                System.currentTimeMillis() - startedAt, content.length());
        return content;
    }

    /**
     * 把上游的错误翻译成对调用方有意义的异常。
     *
     * <h2>⚠️ 401/403 要分两种人来看</h2>
     *
     * <p><b>访客自带的 key 被拒 → 400</b>：是调用方给的东西有问题。
     * 前端该弹"重新填写 key"，而不是"重试"——重试一万次也还是 401。
     *
     * <p><b>服务端配的 key 被拒 → 502</b>：调用方什么都做不了，
     * 是部署方配错了。而且这种情况必须在日志里喊出来，因为它是**部署事故**：
     * 整站的 AI 功能都不可用了，但表面上只有一个 502。
     */
    private RuntimeException translateUpstreamError(AiCredentials credentials,
                                                    RestClientResponseException e) {
        int status = e.getStatusCode().value();
        String body = abbreviate(e.getResponseBodyAsString());

        // ⚠️ 日志里只记 providerName()，**绝不记 apiKey**。
        //    这是 key 泄露最常见的路径：某天有人为了排查问题，
        //    顺手把整个请求（含 Authorization 头）打了出来。
        log.error("大模型返回错误 status={} endpoint={} body={}",
                status, credentials.providerName(), body);

        if (status == 401 || status == 403) {
            if (credentials.userSupplied()) {
                return new InvalidAiCredentialsException(
                        "你填的 AI Key 被上游拒绝了（HTTP " + status + "）。"
                                + "请检查 key 是否完整、是否已过期或额度用尽。");
            }
            log.error("⚠️ 服务端配置的 AI Key 被上游拒绝——这是部署问题，"
                    + "整站的 AI 功能都会不可用。请检查 DEEPSEEK_API_KEY。");
        }

        return new AiServiceException(
                "AI 服务返回错误（HTTP " + status + "），请稍后重试", e);
    }

    /**
     * 拼出这次要访问的完整地址。
     *
     * <p><b>⚠️ baseUrl 只可能来自两个地方</b>：{@link AiProvider} 白名单常量，
     * 或者部署者设的环境变量 {@code AI_BASE_URL}。**请求里的任何字符串都到不了这里。**
     * 这是防 SSRF 的最后一道，改这个方法之前先想清楚这一点。
     *
     * <p>去掉结尾多余的斜杠：用户配环境变量时很容易写成 {@code .../v1/}，
     * 直接拼会变成 {@code .../v1//chat/completions}——多数服务端能容忍，
     * 但不该指望它。
     */
    private static String completionsUri(AiCredentials credentials) {
        String base = credentials.baseUrl();
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("AI base-url 为空，无法发起调用");
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + COMPLETIONS_PATH;
    }

    /**
     * 从响应里取正文。
     *
     * <p>每一层判空都对应一种真实的失败：
     * <ul>
     *   <li>{@code response} 为 null —— 响应体是空的（204，或者上游返回了非 JSON）</li>
     *   <li>{@code choices} 为空 —— 触发了内容审核，或模型拒答</li>
     *   <li>{@code content} 为空白 —— 达到了 max_tokens 上限，正文还没开始就被截断</li>
     * </ul>
     *
     * <p>把它们翻译成明确的中文报错，比让一个 NullPointerException 冒到
     * GlobalExceptionHandler 里强得多——后者用户只会看到「服务器内部错误」。
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
