package com.example.personality.amap;

import com.example.personality.domain.Weather;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Optional;

/**
 * 真的去调高德的天气接口。
 *
 * <p><b>只在 {@code app.amap.enabled=true} 时装配。</b>
 *
 * <p>用的是「实时天气」接口（{@code extensions=base}），不是预报。
 * 预报（{@code extensions=all}）返回未来几天的数据、结构完全不同，
 * 而"此刻的处境"只关心此刻——推荐是给"现在去哪"用的，
 * 明天要不要去是另一个产品问题。
 */
@Component
@ConditionalOnProperty(name = "app.amap.enabled", havingValue = "true")
public class AmapWeatherProvider implements WeatherProvider {

    private static final Logger log = LoggerFactory.getLogger(AmapWeatherProvider.class);

    private static final String PATH = "/v3/weather/weatherInfo";

    private final AmapClient client;

    public AmapWeatherProvider(AmapClient client) {
        this.client = client;
    }

    @Override
    public Optional<Weather> current(String adcode) {
        if (adcode == null || adcode.isBlank()) {
            // 逆地理编码没给出 adcode 时不该往下走——高德会当成"非法参数"，
            // 白白消耗一次配额，还得靠日志去猜。
            return Optional.empty();
        }

        return client.get(PATH, Map.of("city", adcode, "extensions", "base"))
                .flatMap(AmapWeatherProvider::parse);
    }

    /**
     * 把高德的实时天气响应解析成 {@link Weather}。
     *
     * <p>和逆地理编码那边一样，写成 static 纯函数方便单测——
     * 不联网、不费配额。
     *
     * <p>响应长这样（{@code lives} 是数组，字段全是<b>字符串</b>）：
     * <pre>
     * {"status":"1","count":"1","lives":[
     *    {"province":"浙江","city":"杭州市","adcode":"330100",
     *     "weather":"多云","temperature":"26","humidity":"45", ...}]}
     * </pre>
     */
    static Optional<Weather> parse(JsonNode body) {
        JsonNode lives = body.path("lives");

        // lives 是数组，且高德查不到城市时返回空数组——和 regeocode 那边
        // 的 `[]` 是同一个套路，所以同样要判 isArray。
        if (!lives.isArray() || lives.size() == 0) {
            log.warn("高德天气响应里没有 lives 数据");
            return Optional.empty();
        }

        JsonNode live = lives.get(0);
        String condition = AmapClient.text(live, "weather");
        String rawTemperature = AmapClient.text(live, "temperature");

        if (condition.isBlank()) {
            log.warn("高德天气响应里没有 weather 字段");
            return Optional.empty();
        }

        Double temperature = parseTemperature(rawTemperature);
        if (temperature == null) {
            // ⚠️ 温度解析不出来时，宁可整条天气都不要，也不要用一个编出来的值。
            //
            // 因为温度直接参与"高温惩罚"的判断：随便填个 20 会让酷暑天
            // 悄无声息地少扣分，而填 35 又会凭空多扣。两种都是**静默算错**——
            // 用户看到的是一个正常的结果，只是它不对。
            //
            // 丢掉整条天气的代价只是"少一个因子"（weatherFactor = 1.0），
            // 属于可见、可解释的降级。这和 WeatherKind.UNKNOWN 不惩罚、
            // 逆地理编码失败返回空是同一个取舍：不确定时不要瞎猜。
            log.warn("高德天气响应里的 temperature 无法解析：{}", rawTemperature);
            return Optional.empty();
        }

        return Optional.of(Weather.of(condition, temperature));
    }

    /** 高德把温度也给成字符串（"26"）。解析不了返回 null，由调用方决定怎么办。 */
    private static Double parseTemperature(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String providerName() {
        return "amap:weather";
    }
}
