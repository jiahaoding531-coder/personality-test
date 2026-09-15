package com.example.personality.amap;

import com.example.personality.domain.Weather;
import com.example.personality.domain.WeatherKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 天气响应解析的单元测试。
 *
 * <p><b>不联网、不需要 Key、不消耗配额</b>——{@code parse} 是纯函数。
 *
 * <p>⚠️ 高德把<b>所有字段都返回成字符串</b>（温度是 {@code "26"} 而不是 {@code 26}），
 * 这是最容易写错的地方：直接当成数字用会得到 ClassCastException 或者静默的 0。
 * 下面的用例专门盯着它。
 */
class AmapWeatherProviderTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 真实响应（杭州，实测于 2026-09-15）。 */
    private static final String HANGZHOU = """
            {
              "status": "1", "count": "1", "info": "OK", "infocode": "10000",
              "lives": [{
                "province": "浙江", "city": "杭州市", "adcode": "330100",
                "weather": "多云", "temperature": "26",
                "winddirection": "东", "windpower": "≤3", "humidity": "45",
                "reporttime": "2026-09-15 19:03:25",
                "temperature_float": "26.0", "humidity_float": "45.0"
              }]
            }
            """;

    private static Optional<Weather> parse(String json) {
        return AmapWeatherProvider.parse(JSON.readTree(json));
    }

    @Test
    @DisplayName("正常响应：天气和温度都解析出来")
    void parsesOrdinaryResponse() {
        Weather weather = parse(HANGZHOU).orElseThrow();

        assertEquals("多云", weather.condition());
        assertEquals(26.0, weather.temperature(), 1e-9);
        assertEquals(WeatherKind.CLOUDY, weather.kind());
        assertEquals("多云 26°", weather.label());
    }

    @Test
    @DisplayName("⚠️ 温度是字符串，别当成数字读")
    void temperatureIsAString() {
        // 高德返回的是 "temperature":"26"（带引号）。用 asInt() / asDouble()
        // 直接读要么抛异常、要么悄悄得到 0——而 0°C 会让"严寒惩罚"生效，
        // 于是夏天也被当成寒冬，还没有任何报错。
        Weather weather = parse(HANGZHOU).orElseThrow();
        assertEquals(26.0, weather.temperature(), 1e-9);
    }

    @Test
    @DisplayName("空 lives 数组算「没结果」，不算错误")
    void emptyLivesYieldsEmpty() {
        // 和 regeocode 那边一样：高德查不到时会返回空数组而不是报错
        String empty = """
                {"status": "1", "info": "OK", "infocode": "10000", "lives": []}
                """;
        assertTrue(parse(empty).isEmpty());
    }

    @Test
    @DisplayName("温度解析不出来时整条丢弃，而不是拿一个编出来的值去算")
    void unparseableTemperatureDiscardsWholeWeather() {
        // 随便填个温度都是**静默算错**：填 20 会让酷暑天悄无声息地少扣分，
        // 填 35 又会凭空多扣。宁可整条天气不要——那样至少是可解释的降级
        // （weatherFactor = 1.0，等于这一项不存在）。
        String badTemp = HANGZHOU.replace("\"temperature\": \"26\"", "\"temperature\": \"--\"");
        assertTrue(parse(badTemp).isEmpty(), "温度读不出来时应当整条丢弃");
    }

    @Test
    @DisplayName("缺 weather 字段时也丢弃")
    void missingConditionYieldsEmpty() {
        String noCondition = HANGZHOU.replace("\"weather\": \"多云\",", "");
        assertTrue(parse(noCondition).isEmpty());
    }

    @Test
    @DisplayName("各种真实的天气描述串都能归到类别上")
    void mapsRealAmapConditionStrings() {
        // 这些是高德文档里真实存在的取值，挑的是最容易归错类的几种
        List<String> rainLike = List.of("小雨", "中雨", "大到暴雨", "雷阵雨",
                "雷阵雨伴有冰雹", "雨夹雪", "小到中雨");
        for (String condition : rainLike) {
            assertEquals(WeatherKind.RAIN, WeatherKind.fromCondition(condition),
                    "「" + condition + "」应该按下雨处理");
        }

        assertEquals(WeatherKind.CLEAR, WeatherKind.fromCondition("晴"));
        assertEquals(WeatherKind.CLOUDY, WeatherKind.fromCondition("多云"));
        assertEquals(WeatherKind.OVERCAST, WeatherKind.fromCondition("阴"));
        assertEquals(WeatherKind.HAZE, WeatherKind.fromCondition("霾"));
        assertEquals(WeatherKind.HAZE, WeatherKind.fromCondition("强沙尘暴"));
        assertEquals(WeatherKind.UNKNOWN, WeatherKind.fromCondition("没见过的天气"));
        assertEquals(WeatherKind.UNKNOWN, WeatherKind.fromCondition(null));
    }

    @Test
    @DisplayName("⚠️ 复合描述按更严重的算：「多云转小雨」是雨，不是多云")
    void compoundConditionsTakeTheMoreSevereOne() {
        // 判断顺序（先雨雪、再雾霾、最后晴云阴）就是为了这个。
        // 顺序写反的话，"多云转小雨"会被归成多云——于是下着雨却不扣分。
        assertEquals(WeatherKind.RAIN, WeatherKind.fromCondition("多云转小雨"));
        assertEquals(WeatherKind.RAIN, WeatherKind.fromCondition("阴转阵雨"));
        assertEquals(WeatherKind.SNOW, WeatherKind.fromCondition("多云转小雪"));
    }
}
