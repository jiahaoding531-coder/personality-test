package com.example.personality.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 高德开放平台相关配置，绑定 {@code application.yml} 里 {@code app.amap.*} 开头的项。
 *
 * <p>写法和 {@link AiProperties} 完全一致（{@code @ConfigurationProperties} +
 * {@code @Component}，字段名走中划线风格的宽松绑定）。这不只是为了好看——
 * 两个外部依赖用同一套配置范式，读代码的人学会一个就等于学会两个。
 *
 * <h2>为什么键叫 {@code app.amap} 而不是 {@code app.map}</h2>
 *
 * <p>因为它绑定的是一家具体的服务商。将来要换百度地图，配置整段换掉即可，
 * 而 Java 侧那一层（{@code WeatherProvider} / {@code LocationResolver} 接口）
 * 一行不用动——这正是当初把它们抽成接口的意义。
 */
@Component
@ConfigurationProperties(prefix = "app.amap")
public class AmapProperties {

    /**
     * 是否启用真实的高德调用。
     *
     * <p><b>默认 false</b>，这时容器里装配的是桩实现（拿不到天气、拿不到地名，
     * 但推荐照常工作）。理由和 {@link AiProperties#isEnabled()} 一模一样：
     * 别人 clone 仓库时手上多半没有 key，默认开启会让他一启动就报错。
     */
    private boolean enabled = false;

    /**
     * 高德 Web 服务 API Key。
     *
     * <p><b>⚠️ 必须是「Web 服务」类型的 Key。</b>
     * 高德的 Key 分 Web 服务 / Web 端(JS API) / Android / iOS 四类，
     * 用错了会返回 {@code USERKEY_PLAT_NOMATCH}——而那个报错信息
     * 并不会告诉你"你该去申请另一个类型的 Key"，很容易卡住。
     *
     * <p><b>⚠️ 绝对不要把真实 Key 写进 application.yml。</b>
     * 那个文件要提交到 Git，Key 一旦推上去，即使马上删除，
     * 历史提交里依然查得到，爬虫会在几分钟内扫到并盗用额度。
     *
     * <p>正确做法是在环境变量里设 {@code AMAP_KEY}，
     * yml 里只写占位符 {@code ${AMAP_KEY:}}，或者写在
     * {@code application-local.yml} 里（该文件已被 .gitignore 忽略）。
     */
    private String key = "";

    /** 高德 Web 服务的根地址。所有接口都挂在它下面，所以只需要配这一处。 */
    private String baseUrl = "https://restapi.amap.com";

    /**
     * 单次请求超时。
     *
     * <p>默认 <b>5 秒</b>，比 AI 那边的 30 秒短得多——因为这两个是完全不同的东西：
     * 大模型是在"生成内容"，慢是正常的；而逆地理编码和天气是"查一个已经存在的值"，
     * 几百毫秒就该回来。
     *
     * <p>更要紧的是：<b>这是推荐请求链路里的同步调用。</b>用户点一下要等
     * 定位 + 天气两次外部请求，每次卡 30 秒的话，页面就是转圈转一分钟。
     * 宁可超时后降级（没有天气也照样能推荐），也不要让用户干等。
     */
    private Duration timeout = Duration.ofSeconds(5);

    /**
     * 天气的缓存时长。
     *
     * <p><b>这个缓存不是性能优化，是配额保护。</b>个人 Key 的天气接口有每日
     * 调用上限，而推荐是"用户点一下就走一次"的高频操作——
     * 不缓存的话，一个下午的反复调试就能把当天额度用光。
     *
     * <p>10 分钟是"够实时"和"够省"之间的折中：高德的实时天气本身也是
     * 十几分钟更新一次，缓 10 分钟拿到的东西和现查几乎没有区别。
     *
     * <p>按 adcode（城市）缓存，所以同一个城市的所有用户共享一份。
     */
    private Duration weatherCacheTtl = Duration.ofMinutes(10);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Duration getWeatherCacheTtl() {
        return weatherCacheTtl;
    }

    public void setWeatherCacheTtl(Duration weatherCacheTtl) {
        this.weatherCacheTtl = weatherCacheTtl;
    }
}
