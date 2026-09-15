package com.example.personality.amap;

import com.example.personality.config.AmapProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * 调高德 Web 服务接口的薄封装：拼 URL、带 key、判响应、吞异常。
 *
 * <h2>⚠️ 这里最重要的设计：失败一律返回 {@link Optional#empty()}，从不抛异常</h2>
 *
 * <p>这是它和 {@code DeepSeekAiReportGenerator} 最根本的区别。那个类失败了要抛
 * {@code AiServiceException}（接口返回 502），因为<b>AI 报告本身就是用户要的东西</b>——
 * 拿不到就是这次请求失败了。
 *
 * <p>但高德在这里提供的是<b>锦上添花</b>：天气、地名。用户要的是"推荐去哪"，
 * 天气拿不到顶多是少一个因子（{@code weatherFactor} 恒为 1.0），
 * <b>"没有天气的推荐"仍然是一个完整可用的推荐</b>。
 *
 * <p>所以：上游 500、网络超时、配额耗尽、key 过期——全都只记日志，返回空。
 * 一个查天气的接口挂了，不该让整个推荐功能不可用。
 *
 * <h2>为什么 key 直接写进查询串</h2>
 *
 * <p>高德的鉴权就是 URL 参数 {@code ?key=xxx}，没有 Bearer 头那种形式。
 * 这意味着 <b>key 会出现在日志和代理记录里</b>——所以下面记日志时
 * 特意用 {@code path} 而不是完整 URL，避免把 key 打进日志文件。
 */
@Component
@ConditionalOnProperty(name = "app.amap.enabled", havingValue = "true")
public class AmapClient {

    private static final Logger log = LoggerFactory.getLogger(AmapClient.class);

    private final RestClient restClient;
    private final String key;

    public AmapClient(AmapProperties properties) {
        this.key = properties.getKey();

        // 配置错误要在启动时就炸掉，不能等到用户点推荐才发现。
        // ⚠️ 注意这和上面说的"运行期失败一律吞掉"不矛盾：
        //    - 「开了开关却没给 key」是**配置写错了**，越早发现越好
        //    - 「网络不通 / 配额用完」是**运行期波动**，该降级就降级
        // 两者的区别是"重试有没有用"：前者重试一万次还是错，后者过一会儿就好了。
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "app.amap.enabled=true 但未配置高德 Key。"
                            + "请设置环境变量 AMAP_KEY（或在 application-local.yml 里配置 app.amap.key）后重启应用。");
        }

        // 超时必须设。不设默认就是"无限等待"，上游卡住会把 Tomcat 的请求线程
        // 一个个占满。这条和 AI 那边是同一个教训。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = properties.getTimeout();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);

        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * 发一个 GET，返回高德的响应体。
     *
     * @param path   接口路径，比如 {@code /v3/geocode/regeo}
     * @param params 业务参数（key 由本方法自动带上，调用方不用管）
     * @return 响应 JSON；任何形式的失败都返回空
     */
    public Optional<JsonNode> get(String path, Map<String, String> params) {
        JsonNode body;
        try {
            body = restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path(path);
                        uriBuilder.queryParam("key", key);
                        params.forEach(uriBuilder::queryParam);
                        return uriBuilder.build();
                    })
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            // 上游返回了 4xx / 5xx。把 info/infocode 一起记下来——
            // 高德的错误说明全在那两个字段里，不记就只能靠猜。
            log.warn("高德接口返回错误 path={} status={} body={}",
                    path, e.getStatusCode(), abbreviate(e.getResponseBodyAsString()));
            return Optional.empty();
        } catch (ResourceAccessException e) {
            // 连不上 或 超时。国内调高德一般不会遇到，但代理/VPN 开着的时候会。
            log.warn("高德接口连接失败 path={} msg={}", path, e.getMessage());
            return Optional.empty();
        }

        return checkEnvelope(path, body);
    }

    /**
     * 检查高德自己的响应信封。
     *
     * <p><b>⚠️ 高德 HTTP 状态码永远是 200。</b>哪怕 key 错了、配额用完了，
     * 它也是 200 + 一个 {@code status: "0"} 的包体。所以"HTTP 通了"
     * 完全不等于"这次调用成功了"，必须再看 body 里的 status。
     *
     * <p>常见的 {@code infocode}：
     * <ul>
     *   <li>{@code 10000} 成功</li>
     *   <li>{@code 10001} key 不正确或已过期</li>
     *   <li>{@code 10003} 日调用量已用完</li>
     *   <li>{@code 10009} 请求 key 与绑定平台不符（就是"Key 类型选错了"）</li>
     * </ul>
     * 把这几个记进日志，排查时能省掉一轮搜索。
     */
    private Optional<JsonNode> checkEnvelope(String path, JsonNode body) {
        if (body == null) {
            log.warn("高德接口返回空响应 path={}", path);
            return Optional.empty();
        }

        // ⚠️ status 是**字符串** "1" / "0"，不是数字 1 / 0。
        // 写成 body.path("status").asInt() == 1 会永远不成立，
        // 而且因为返回的是 0 而不是抛异常，表现得就像"接口一直失败"。
        String status = text(body, "status");
        if (!"1".equals(status)) {
            log.warn("高德接口业务失败 path={} info={} infocode={}",
                    path, text(body, "info"), text(body, "infocode"));
            return Optional.empty();
        }
        return Optional.of(body);
    }

    /**
     * 安全地取一个字符串字段。
     *
     * <p>高德的响应字段类型很不稳定：同一个 {@code city}，普通城市返回字符串，
     * <b>直辖市（北京/上海/天津/重庆）返回的是空数组 {@code []}</b>。
     * 直接 {@code asString()} 碰上数组节点会得到意想不到的结果，
     * 所以这里统一用 {@code path()}（缺失返回 MissingNode 而不是抛异常）
     * 再显式判空，取不到就当空串。
     */
    static String text(JsonNode node, String field) {
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
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
