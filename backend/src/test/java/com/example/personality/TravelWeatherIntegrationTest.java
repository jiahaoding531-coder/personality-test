package com.example.personality;

import com.example.personality.amap.LocationResolver;
import com.example.personality.amap.WeatherProvider;
import com.example.personality.domain.LocationInfo;
import com.example.personality.domain.Weather;
import com.example.personality.service.WeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「天气参与推荐打分」的端到端测试。
 *
 * <h2>为什么必须用假的 provider</h2>
 *
 * <p>真高德此刻在杭州报的是"多云"——<b>多云不产生任何惩罚</b>，
 * 所以拿真天气去测，weatherFactor 会一直是 1.0，等于什么都没验证到。
 * 这个测试要证明的是"下雨天户外的地方真的会被压下去"，
 * 那就得能<b>控制</b>天气是什么。
 *
 * <p>所以这里用 {@link TestConfiguration} 把 {@code LocationResolver} 和
 * {@code WeatherProvider} 换成假的（{@code @Primary} 覆盖掉桩实现）。
 * 好处是这个测试<b>完全离线</b>：不需要 key、不消耗配额、不受网络影响，
 * 放进 CI 里跑也没问题。
 *
 * <p>真实现的解析逻辑由 {@code AmapWeatherProviderTest} 单独覆盖——
 * 两边合起来，从"高德的 JSON"到"排序真的变了"整条链路都有测试。
 */
class TravelWeatherIntegrationTest extends IntegrationTestBase {

    /** 西湖坐标，和其他旅行测试保持一致。 */
    private static final double WEST_LAKE_LAT = 30.2420;
    private static final double WEST_LAKE_LNG = 120.1400;

    @Autowired
    private WeatherService weatherService;

    /**
     * ⚠️ 天气缓存是**进程级单例**，{@code @Transactional} 回滚不了它。
     *
     * <p>不清的话，第一个用例把"下雨"缓存进去，第二个用例改成"晴天"，
     * 拿到的还是缓存里那个"下雨"——于是用例之间产生隐式依赖，
     * 单独跑每个都过、一起跑就随机失败。
     *
     * <p>这和 {@code IntegrationTestBase} 里手动清限流器计数是同一类问题：
     * <b>凡是跨测试共享的可变状态，都要显式重置。</b>
     */
    @BeforeEach
    void clearWeatherCache() {
        weatherService.clearCache();
        FakeWeatherProvider.current = Weather.of("中雨", 18);
    }

    // ==========================================================
    // 核心：天气真的改变了打分
    // ==========================================================

    @Test
    @DisplayName("【核心】下雨天：户外地点被砍半，室内地点几乎不受影响")
    void rainAffectsOutdoorPlacesButNotIndoorOnes() throws Exception {
        List<JsonNode> places = recommendWith(Weather.of("中雨", 18));

        List<Double> factors = new ArrayList<>();
        for (JsonNode place : places) {
            factors.add(place.get("scoreBreakdown").get("weather").asDouble());
        }

        // 西湖边的 Top 3 里必然有完全户外的（西湖·苏堤 indoor=0，餐厅 indoor=80 左右）。
        // 所以应该能看到"被压的那个"和"基本没动的那些"同时存在。
        assertTrue(factors.contains(0.5),
                "中雨的惩罚是 0.5，完全户外的地点应该正好砍半，实际：" + factors);
        assertTrue(factors.stream().anyMatch(f -> f > 0.5),
                "室内的地方不该被同等对待，实际：" + factors);

        for (double factor : factors) {
            assertTrue(factor <= 1.0, "天气因子不能超过 1（会撞数据库 CHECK）：" + factor);
            assertTrue(factor >= 0.5, "惩罚最多砍半，不会归零：" + factor);
        }
    }

    @Test
    @DisplayName("天气会出现在 appliedContext 里，并标明它有没有真的影响排序")
    void weatherIsReportedBackToTheUser() throws Exception {
        JsonNode response = recommendRaw(Weather.of("中雨", 18));

        JsonNode weather = response.get("appliedContext").get("weather");
        assertFalse(weather.isNull() || weather.isMissingNode(), "响应里应该带上天气");

        assertEquals("中雨", weather.get("condition").asString());
        assertEquals(18.0, weather.get("temperature").asDouble(), 1e-9);
        assertEquals("中雨 18°", weather.get("label").asString());
        assertTrue(weather.get("affectsRecommendation").asBoolean(),
                "下雨确实改变了排序，必须如实告诉用户");
    }

    @Test
    @DisplayName("晴天：天气照样显示，但要标明它没影响排序")
    void clearWeatherIsShownButMarkedAsNotAffecting() throws Exception {
        JsonNode response = recommendRaw(Weather.of("晴", 24));

        JsonNode weather = response.get("appliedContext").get("weather");
        assertEquals("晴 24°", weather.get("label").asString());

        // ⚠️ 这一条是诚实的落点：晴天也在温度计上，但说"我按天气调整了推荐"
        // 就是撒谎。前端据此决定不给那句提示。
        assertFalse(weather.get("affectsRecommendation").asBoolean(),
                "晴天不产生任何惩罚，不能声称调整过排序");

        for (JsonNode place : response.get("places")) {
            assertEquals(1.0, place.get("scoreBreakdown").get("weather").asDouble(), 1e-9,
                    "晴天时天气因子必须恰好是 1.0");
        }
    }

    @Test
    @DisplayName("五因子相乘仍然精确等于最终分（下雨时也一样）")
    void breakdownStillMultipliesOutExactlyUnderRain() throws Exception {
        JsonNode response = recommendRaw(Weather.of("大雨", 16));

        for (JsonNode place : response.get("places")) {
            JsonNode b = place.get("scoreBreakdown");
            double product = b.get("interest").asDouble()
                    * b.get("distance").asDouble()
                    * b.get("quality").asDouble()
                    * b.get("state").asDouble()
                    * b.get("weather").asDouble();

            assertEquals(b.get("finalScore").asDouble(), product, 1e-9,
                    place.get("name").asString() + " 的拆解对不上——拆解和实际打分不一致，"
                            + "比不给拆解更糟（用户看到的是假账）");
        }
    }

    @Test
    @DisplayName("没拿到天气时，响应里没有天气、打分也不受影响")
    void missingWeatherDegradesGracefully() throws Exception {
        FakeWeatherProvider.current = null;   // 模拟高德挂了 / 没配 key
        weatherService.clearCache();

        // 关键：**不能报错**，必须照常返回推荐
        JsonNode response = recommendRaw(null);

        assertTrue(response.get("appliedContext").get("weather").isNull(),
                "拿不到天气就该是 null，而不是编一个");
        assertFalse(response.get("places").isEmpty(), "没有天气也必须给出推荐");

        for (JsonNode place : response.get("places")) {
            assertEquals(1.0, place.get("scoreBreakdown").get("weather").asDouble(), 1e-9,
                    "没有天气时天气因子必须是 1.0（乘法单位元），否则所有分数都会漂移");
        }
    }

    // ==========================================================
    // 辅助
    // ==========================================================

    private List<JsonNode> recommendWith(Weather weather) throws Exception {
        List<JsonNode> places = new ArrayList<>();
        recommendRaw(weather).get("places").forEach(places::add);
        return places;
    }

    /** 走完整流程（建会话 → 答题 → 提交 → 推荐），返回推荐响应。 */
    private JsonNode recommendRaw(Weather weather) throws Exception {
        FakeWeatherProvider.current = weather;
        weatherService.clearCache();

        MockHttpSession login = registerAndLogin(uniqueUsername("weather"), "Passw0rd!");
        TestSessionRef ref = createTravelSession(login);
        answerAllTravelQuestions(ref, 4, login);
        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/submit", ref.id())
                .with(csrf()).session(login), ref)).andExpect(status().isOk());

        MvcResult result = mockMvc.perform(withToken(
                        post("/api/travel/sessions/{id}/recommendations", ref.id())
                                .with(csrf())
                                .session(login)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of(
                                        "latitude", WEST_LAKE_LAT,
                                        "longitude", WEST_LAKE_LNG,
                                        "remainingMinutes", 240))),
                        ref))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(
                new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    // ==========================================================
    // 测试替身
    // ==========================================================

    /**
     * 把两个 provider 换成假的。
     *
     * <p>{@code @Primary} 让它们盖过 {@code StubLocationResolver} /
     * {@code StubWeatherProvider}（那两个在测试 profile 下是装配着的）。
     * 用 {@code @Primary} 而不是覆盖 Bean 定义，是因为 Spring 默认
     * 禁止 Bean 定义覆盖（{@code allow-bean-definition-overriding=false}）。
     */
    @TestConfiguration
    static class FakeAmapConfiguration {

        @Bean
        @Primary
        LocationResolver fakeLocationResolver() {
            return new FakeLocationResolver();
        }

        @Bean
        @Primary
        WeatherProvider fakeWeatherProvider() {
            return new FakeWeatherProvider();
        }
    }

    /** 永远返回杭州，让推荐链路能走到"查天气"那一步。 */
    static final class FakeLocationResolver implements LocationResolver {

        @Override
        public Optional<LocationInfo> resolve(double latitude, double longitude) {
            return Optional.of(new LocationInfo(
                    "浙江省", "杭州市", "西湖区", "北山街", "西湖街道",
                    "330100", "浙江省杭州市西湖区北山街101号"));
        }

        @Override
        public String providerName() {
            return "fake";
        }
    }

    /** 天气由测试指定；{@code current} 设成 null 表示"查不到"。 */
    static final class FakeWeatherProvider implements WeatherProvider {

        /** static 是因为 @Bean 每次上下文刷新才创建一次，而测试要能中途改它。 */
        static Weather current = Weather.of("中雨", 18);

        @Override
        public Optional<Weather> current(String adcode) {
            return Optional.ofNullable(current);
        }

        @Override
        public String providerName() {
            return "fake";
        }
    }
}
