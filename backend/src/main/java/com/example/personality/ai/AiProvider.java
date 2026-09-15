package com.example.personality.ai;

import java.util.Locale;
import java.util.Optional;

/**
 * 允许访客自带 key 的大模型厂商**白名单**。
 *
 * <h2>⚠️ 这个枚举最重要的作用是防 SSRF，不是"方便选厂商"</h2>
 *
 * <p>访客请求里只能带一个**厂商标识**（比如 {@code DEEPSEEK}），
 * 真实 URL 由这里的常量决定。**请求里的任何字符串都不会变成我们要访问的地址。**
 *
 * <p>为什么这条必须守死：如果允许请求带 base-url，我们的服务器就成了一台
 * <b>SSRF 跳板</b>——攻击者可以让我们去请求：
 * <ul>
 *   <li>{@code http://192.168.x.x}、{@code http://10.x.x.x} —— 内网里的服务</li>
 *   <li>{@code http://169.254.169.254} —— 云厂商的元数据端点，
 *       能读到实例的临时凭证，这是云上最经典的失陷路径</li>
 *   <li>{@code http://localhost:8080} —— 我们自己</li>
 * </ul>
 * 而响应体还会被当成"AI 返回的内容"一路展示出来，等于把内网数据直接读给攻击者看。
 *
 * <p>所以这里<b>没有任何 setter</b>，也不接受构造参数——URL 只能来自源码里这几行常量。
 *
 * <h2>四家都是 OpenAI 兼容协议</h2>
 *
 * <p>所以 {@code DeepSeekChatClient} 里写死的 {@code /chat/completions} 路径
 * 一个字都不用改。这是当初把 HTTP 层从 {@code DeepSeekAiReportGenerator} 里
 * 抽成公共类的又一次兑现。
 */
public enum AiProvider {

    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),

    DASHSCOPE("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),

    ZHIPU("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),

    MOONSHOT("Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k");

    private final String label;
    private final String baseUrl;
    private final String defaultModel;

    AiProvider(String label, String baseUrl, String defaultModel) {
        this.label = label;
        this.baseUrl = baseUrl;
        this.defaultModel = defaultModel;
    }

    /** 中文展示名，给前端下拉框用。 */
    public String label() {
        return label;
    }

    /** 接口根地址。<b>只来自这个枚举，永远不来自请求。</b> */
    public String baseUrl() {
        return baseUrl;
    }

    /** 这个厂商的默认模型。访客不用自己填模型名。 */
    public String defaultModel() {
        return defaultModel;
    }

    /**
     * 把请求头里的厂商标识转成枚举。
     *
     * <p>大小写不敏感、自动去空白——这些都只是为了让用户少踩格式的坑。
     *
     * <p>⚠️ <b>认不出来时返回空，由调用方明确拒绝（400）。</b>
     * 不要"不认识就退回默认厂商"：那样用户填错了却看不出，还以为在用通义，
     * 实际调的是 DeepSeek，而且账单和输出风格都对不上。
     *
     * <p>返回空还有一层意思：**任何传进来的字符串都不会被当成地址使用**。
     * 这里只做"查表命中"，没有"拿它拼 URL"这种可能。
     */
    public static Optional<AiProvider> fromName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        for (AiProvider provider : values()) {
            if (provider.name().equals(normalized)) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }

    /** 给报错信息用：列出所有合法取值，用户不用去翻文档。 */
    public static String supportedNames() {
        StringBuilder sb = new StringBuilder();
        for (AiProvider provider : values()) {
            if (!sb.isEmpty()) {
                sb.append("、");
            }
            sb.append(provider.name());
        }
        return sb.toString();
    }
}
