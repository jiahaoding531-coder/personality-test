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
     * 是否启用真实的 AI 调用。
     *
     * <p><b>默认 false</b>，这时容器里装配的是 {@code StubAiReportGenerator}
     * （调用返回 501）。这样设计的原因很实际：别人 clone 你的仓库时，
     * 手上多半没有 API Key，如果默认开启，他一启动就报错，
     * 还得先读懂代码才知道要关掉什么。
     */
    private boolean enabled = false;

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
