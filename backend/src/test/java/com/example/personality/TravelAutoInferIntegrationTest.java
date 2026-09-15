package com.example.personality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「系统自己推断处境」的端到端测试。
 *
 * <h2>⚠️ 这个类为什么必须固定时钟</h2>
 *
 * <p>自动推断是按时间来的：<b>饭点推断"想吃饭"，别的时候什么都不推</b>。
 * 如果测试用真实的 {@code LocalTime.now()}，就会变成：
 * <ul>
 *   <li>中午跑 → 绿</li>
 *   <li>下午跑 → 红</li>
 * </ul>
 * 这种"看时间脸色"的测试比没有测试更糟——它会训练人忽略红灯。
 *
 * <p>所以这里给容器塞了一个固定在<b>北京时间 12:30</b> 的 {@link Clock}
 * （{@code 04:30Z}，午饭时段内）。这样"饭点会推断想吃饭"这件事
 * 才成为一条**确定**的断言，而不是碰运气。
 *
 * <p>对应地，{@code ContextInferrerTest} 用固定时间覆盖了所有时段边界，
 * 这个类只管"推断结果有没有真的走进推荐链路"。
 */
class TravelAutoInferIntegrationTest extends IntegrationTestBase {

    private static final double WEST_LAKE_LAT = 30.2420;
    private static final double WEST_LAKE_LNG = 120.1400;

    /** 北京时间 12:30 —— 午饭时段内。 */
    @TestConfiguration
    static class FixedLunchClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(
                    Instant.parse("2026-09-15T04:30:00Z"),
                    ZoneId.of("Asia/Shanghai"));
        }
    }

    @Test
    @DisplayName("饭点开自动模式 → 系统推断出「想吃饭」，并在响应里标明是它猜的")
    void autoInferAtLunchInfersHungry() throws Exception {
        TestSessionRef ref = readySession();

        JsonNode response = recommend(ref, Map.of(
                "latitude", WEST_LAKE_LAT,
                "longitude", WEST_LAKE_LNG,
                "autoInfer", true));

        JsonNode appliedContext = response.get("appliedContext");
        assertEquals("12:30", appliedContext.get("now").asText(),
                "时钟被固定住了，所以这个时间应该是确定的");

        // ⚠️ 关键：系统猜出来的状态必须原样回传，而且单独标出来。
        // 用户从没说过"我饿了"——是系统自己猜的，所以最该让用户看得见、改得掉。
        JsonNode inferred = appliedContext.get("inferredStates");
        assertEquals(1, inferred.size(), "午饭点应该推断出一个状态，实际：" + inferred);
        assertEquals("HUNGRY", inferred.get(0).get("key").asText());
        assertEquals("想吃饭了", inferred.get(0).get("label").asText(),
                "中文名由后端给，前端不用自己维护翻译表");

        // 它也确实进入了这次推荐用的状态里
        assertEquals(1, appliedContext.get("states").size());
        assertEquals("HUNGRY", appliedContext.get("states").get(0).get("key").asText());
    }

    @Test
    @DisplayName("不开自动模式 → 同样在饭点，系统也不该自作主张")
    void withoutAutoInferNothingIsGuessed() throws Exception {
        TestSessionRef ref = readySession();

        JsonNode response = recommend(ref, Map.of(
                "latitude", WEST_LAKE_LAT,
                "longitude", WEST_LAKE_LNG));

        JsonNode appliedContext = response.get("appliedContext");
        assertEquals(0, appliedContext.get("inferredStates").size(),
                "用户没让系统猜，就不该猜");
        assertEquals(0, appliedContext.get("states").size());
    }

    @Test
    @DisplayName("用户自己说的状态优先：说了「累了」不会把系统猜的「想吃饭」挤掉")
    void userStateAndInferredStateCoexist() throws Exception {
        TestSessionRef ref = readySession();

        JsonNode response = recommend(ref, Map.of(
                "latitude", WEST_LAKE_LAT,
                "longitude", WEST_LAKE_LNG,
                "autoInfer", true,
                "states", java.util.List.of("TIRED")));

        JsonNode appliedContext = response.get("appliedContext");
        // 两个状态并存：一个是用户说的，一个是系统猜的
        assertEquals(2, appliedContext.get("states").size(),
                "用户说的和系统猜的应该都在，而不是互相覆盖");

        // 而且只有"想吃饭"被标成猜的——用户没说过它
        assertEquals(1, appliedContext.get("inferredStates").size());
        assertEquals("HUNGRY", appliedContext.get("inferredStates").get(0).get("key").asText());
    }

    @Test
    @DisplayName("推断确实改变了推荐结果，不只是回传了个字段")
    void inferenceActuallyAffectsTheRecommendation() throws Exception {
        TestSessionRef ref = readySession();

        JsonNode withoutAuto = recommend(ref, Map.of(
                "latitude", WEST_LAKE_LAT, "longitude", WEST_LAKE_LNG));
        JsonNode withAuto = recommend(ref, Map.of(
                "latitude", WEST_LAKE_LAT, "longitude", WEST_LAKE_LNG, "autoInfer", true));

        String topWithout = withoutAuto.get("places").get(0).get("name").asText();
        String topWith = withAuto.get("places").get(0).get("name").asText();
        assertFalse(topWithout.equals(topWith),
                "饭点自动推断出「想吃饭」之后，第一名应该换成吃的（原：" + topWithout + "）");

        // 而且推荐依据里能看到美食维度的权重被顶上去了
        boolean foodBoosted = false;
        for (JsonNode reason : withAuto.get("places").get(0).get("reasons")) {
            if ("FOOD".equals(reason.get("dimensionKey").asText())) {
                foodBoosted = true;
            }
        }
        assertTrue(foodBoosted, "第一名应该能说出「因为你想吃饭」");
    }

    // ==========================================================
    // 辅助方法
    // ==========================================================

    /** 一次答完题、提交过、可以推荐的会话。 */
    private TestSessionRef readySession() throws Exception {
        TestSessionRef ref = createTravelSession(null);
        answerAllTravelQuestions(ref, 3, null);
        mockMvc.perform(withToken(
                        post("/api/travel/sessions/{id}/submit", ref.id()).with(csrf()), ref))
                .andExpect(status().isOk());
        return ref;
    }

    private JsonNode recommend(TestSessionRef ref, Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(withToken(
                        post("/api/travel/sessions/{id}/recommendations", ref.id()).with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(body)), ref))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(
                new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }
}
