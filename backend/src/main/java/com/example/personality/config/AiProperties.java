package com.example.personality.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * AI 相关配置，绑定 {@code application.yml} 里 {@code app.ai.*} 开头的项。
 *
 * <p>用 {@code @ConfigurationProperties} 而不是在每个使用处写 {@code @Value("${...}")}，
 * 有三个好处：
 * <ol>
 *   <li><b>集中</b>：所有 AI 配置在一处，一眼看全，不用满项目搜 {@code @Value}</li>
 *   <li><b>类型安全</b>：{@code timeout} 声明成 {@code Duration}，yml 里写 {@code 30s}
 *       就能自动转换；写错了启动时直接报错，而不是运行到那行才炸</li>
 *   <li><b>可校验</b>：配合 {@code @Validated} 加 {@code @NotBlank} 之类的注解，
 *       配置缺失时启动就失败（比在生产环境某个凌晨才暴露强得多）</li>
 * </ol>
 *
 * <p>字段名和 yml 的对应关系是"宽松绑定"：{@code baseUrl} 可以写成
 * {@code base-url}、{@code baseUrl}、甚至 {@code BASE_URL}，Spring 都能认。
     * 本项目统一用中划线风格（{@code base-url}），因为它是 yml 社区的惯例。
 */
@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    /**
     * 是否允许用**服务端配置的这把 key**。
     *
     * <h2>⚠️ 语义已经变了，别再按老意思读</h2>
     *
     * <p>接入"访客自带 key"（BYOK）之前，这个开关的意思是<b>"有没有 AI"</b>：
     * false 就装配桩实现、接口返回 501。
     *
     * <p>现在它的意思收窄成了<b>"这站替不替访客付钱"</b>：
     * <ul>
     *   <li>{@code true} + 配了 key → 访客没带 key 时用服务端这把</li>
     *   <li>{@code false} → 服务端一分钱不花；但访客<b>带上自己的 key 照样能用</b></li>
     * </ul>
     *
     * <p>也就是说 <b>false 不再等于"没有 AI"</b>。真正"没有 AI"的情况是
     * "服务端没配 + 访客也没带"，那时由 {@code AiCredentialsResolver} 返回空，
     * 服务层转成 501——注意那已经是**运行时**的判断，不再是启动时的 Bean 装配条件了。
     *
     * <p>默认 false 的理由没变：别人 clone 仓库直接跑，不该因为缺一个环境变量就报错。
     */
    private boolean enabled = false;

    /**
     * 是否允许访客携带自己的 API Key。
     *
     * <p><b>默认 true</b>，因为这是"把 demo 发给别人看"时唯一不让自己掏钱的形态：
     * 别人打开就能填上自己的 key 用起来，而你一分钱不花。
     *
     * <p>代价是：**部署出去就成了一台"按白名单转发大模型请求"的代理**。
     * 评估过风险边界，是可以接受的：
     * <ul>
     *   <li>调用者必须自己有一把**在厂商侧有效**的 key——他没有 key 就什么也做不了</li>
     *   <li>目标地址来自 {@code AiProvider} 白名单，**请求里的任何字符串都变不成地址**
     *       （SSRF 的入口被这道白名单彻底堵死）</li>
     *   <li>资源占用被 {@link #getTimeout()} 和 Tomcat 的线程池上限约束住</li>
     * </ul>
     *
     * <p>如果你的部署确实不想要这个能力（比如内部系统），把它设成 false 即可——
     * 那时访客带的 key 会被忽略，只认服务端配置。
     */
    private boolean allowUserKeys = true;

    /** DeepSeek 的 OpenAI 兼容端点。换成别的厂商（通义、智谱、Kimi）通常也兼容这个协议。 */
    private String baseUrl = "https://api.deepseek.com/v1";

    /** 模型名。deepseek-chat 是通用对话模型，够用且比 reasoner 快、便宜。 */
    private String model = "deepseek-chat";

    /**
     * API Key。
     *
     * <p><b>⚠️ 绝对不要把真实 Key 写进 application.yml。</b>
     * 那个文件是要提交到 Git 的，Key 一旦推上去，即使马上删除，
     * 历史提交里依然查得到，而且爬虫会在几分钟内扫到并开始盗用你的额度。
     *
     * <p>正确做法是在环境变量里设 {@code DEEPSEEK_API_KEY}，
     * yml 里只写占位符 {@code ${DEEPSEEK_API_KEY:}}（冒号后留空表示默认空字符串）。
     */
    private String apiKey = "";

    /** 单次请求超时。大模型偶尔会慢，30 秒是够用的上限。 */
    private Duration timeout = Duration.ofSeconds(30);

    /** 生成的最大 token 数。400~600 中文字大约 800~1200 token，留点余量。 */
    private int maxTokens = 1200;

    /**
     * 采样温度。人格解读属于创作类任务，用厂商默认值（DeepSeek 是 1.0）即可。
     * 调低会让每次输出趋同，调高会开始跑题。
     */
    private double temperature = 1.0;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAllowUserKeys() {
        return allowUserKeys;
    }

    public void setAllowUserKeys(boolean allowUserKeys) {
        this.allowUserKeys = allowUserKeys;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }
}
