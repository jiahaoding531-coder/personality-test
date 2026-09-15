package com.example.personality.ai;

/**
 * 一次 AI 调用要用的凭据。
 *
 * <h2>⚠️ 这个对象是"用完即弃"的</h2>
 *
 * <p>它只活在一次请求里：从请求头（或服务端配置）取出来，构造出这个对象，
 * 传给 {@link DeepSeekChatClient}，调用结束后就没有引用了。
 *
 * <p><b>绝不落库、绝不进任何缓存、绝不放 {@code @RequestScope} 之外的容器。</b>
 * 也因此不能"按 key 缓存 HTTP 客户端"——那等于在内存里长期持着别人的凭证。
 *
 * <h2>为什么 {@code baseUrl} 是一个字段而不是从 provider 现取</h2>
 *
 * <p>因为存在两种来源：
 * <ul>
 *   <li><b>访客自带</b>：{@code baseUrl} 来自 {@link AiProvider} 白名单，
 *       请求里的任何字符串都不会变成它</li>
 *   <li><b>服务端配置</b>：{@code baseUrl} 来自 {@code AI_BASE_URL}，可能是
 *       白名单之外的端点（比如部署者自己搭的 Ollama）。
 *       这是<b>部署者自己设的环境变量</b>，不是访客能影响的，所以没有 SSRF 问题</li>
 * </ul>
 * 把 URL 收进这个对象，两种来源就能走同一条代码路径。
 *
 * @param provider     访客用的厂商；<b>服务端自定义端点时为 null</b>
 * @param baseUrl      接口根地址（不含 {@code /chat/completions}）
 * @param model        模型名
 * @param apiKey       调用凭证
 * @param userSupplied true = 访客自己带的，false = 服务端配置的。
 *                     <p>用来决定<b>出错时怪谁</b>：访客的 key 被拒是"你的输入有问题"（400），
 *                     服务端的 key 被拒是"这个部署配错了"（502）——两者的处理方式完全不同
 */
public record AiCredentials(
        AiProvider provider,
        String baseUrl,
        String model,
        String apiKey,
        boolean userSupplied
) {

    /** 访客自带：URL 和默认模型都来自白名单，请求影响不了它们。 */
    public static AiCredentials forUser(AiProvider provider, String apiKey) {
        return new AiCredentials(provider, provider.baseUrl(), provider.defaultModel(),
                apiKey, true);
    }

    /** 服务端配置：端点由部署者通过环境变量指定，可以不在白名单里。 */
    public static AiCredentials forServer(String baseUrl, String model, String apiKey) {
        return new AiCredentials(null, baseUrl, model, apiKey, false);
    }

    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 实现方标识，会随响应返回给前端。
     *
     * <p>⚠️ <b>绝对不要把这个方法改成带上 key。</b>
     * 它的返回值会出现在 API 响应里、也会进日志——key 一旦进去就是泄露。
     * 这里只回报"哪个厂商 + 哪个模型"。
     */
    public String providerName() {
        String prefix = provider == null ? "custom" : provider.name().toLowerCase(java.util.Locale.ROOT);
        return prefix + ":" + model;
    }
}
