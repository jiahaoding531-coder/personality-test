package com.example.personality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「画像跨会话复用」的端到端测试。
 *
 * <p>要守的承诺很简单：<b>用户昨天答过，今天打开不该再答一遍。</b>
 *
 * <p>⚠️ 这条能力<b>只对登录用户成立</b>——匿名没有稳定的身份，"上次"就无从谈起。
 * 所以这个类里有一半测试在守那条边界。
 */
class TravelProfileReuseIntegrationTest extends IntegrationTestBase {

    private static final double WEST_LAKE_LAT = 30.2420;
    private static final double WEST_LAKE_LNG = 120.1400;

    // ==========================================================
    // 核心：登录用户不用重答
    // ==========================================================

    @Test
    @DisplayName("【核心】上次测过 → 新会话不答题也能直接拿到画像，并被标明是复用的")
    void profileIsReusedAcrossSessions() throws Exception {
        MockHttpSession login = registerAndLogin(uniqueUsername("reuse"), "Passw0rd!");

        // 第一次：老老实实答完并提交
        TestSessionRef first = createTravelSession(login);
        answerAllTravelQuestions(first, 5, login);
        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/submit", first.id())
                .with(csrf()).session(login), first)).andExpect(status().isOk());

        // 第二次：新建会话，一道题都不答
        TestSessionRef second = createTravelSession(login);

        MvcResult result = mockMvc.perform(
                        get("/api/travel/sessions/{id}/profile", second.id()).session(login))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(second.id()))
                // ⚠️ 这个标记必须有：画像不是这次答出来的，前端要说清楚，
                // 否则用户会以为系统把他没做的测试算完了
                .andExpect(jsonPath("$.reused").value(true))
                .andExpect(jsonPath("$.dimensions.length()").value(8))
                .andReturn();

        JsonNode profile = objectMapper.readTree(
                new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
        assertEquals(100, profile.get("dimensions").get(0).get("score").asInt(),
                "复用过来的应该是上次那份（全选 5 分 → 100）");
    }

    @Test
    @DisplayName("复用的画像能直接拿来推荐——这才是复用的意义")
    void reusedProfileCanBeUsedForRecommendation() throws Exception {
        MockHttpSession login = registerAndLogin(uniqueUsername("reuse_rec"), "Passw0rd!");

        TestSessionRef first = createTravelSession(login);
        answerAllTravelQuestions(first, 4, login);
        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/submit", first.id())
                .with(csrf()).session(login), first)).andExpect(status().isOk());

        // 新会话不答题，直接请求推荐
        TestSessionRef second = createTravelSession(login);
        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/recommendations", second.id())
                        .with(csrf()).session(login)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("latitude", WEST_LAKE_LAT, "longitude", WEST_LAKE_LNG))),
                second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.places.length()").value(3));
    }

    @Test
    @DisplayName("自己测过之后就不再复用——新画像优先")
    void ownProfileTakesPrecedenceOnceSubmitted() throws Exception {
        MockHttpSession login = registerAndLogin(uniqueUsername("fresh"), "Passw0rd!");

        TestSessionRef first = createTravelSession(login);
        answerAllTravelQuestions(first, 5, login);
        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/submit", first.id())
                .with(csrf()).session(login), first)).andExpect(status().isOk());

        // 新会话，这次真的答题了，而且答案不同（全选 1 分）
        TestSessionRef second = createTravelSession(login);
        answerAllTravelQuestions(second, 1, login);
        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/submit", second.id())
                .with(csrf()).session(login), second)).andExpect(status().isOk());

        mockMvc.perform(get("/api/travel/sessions/{id}/profile", second.id()).session(login))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reused").value(false))
                .andExpect(jsonPath("$.dimensions[0].score").value(0.00));
    }

    // ==========================================================
    // 边界：匿名用户没有"上次"
    // ==========================================================

    @Test
    @DisplayName("匿名会话不复用——没有稳定的身份，'上次'就无从谈起")
    void anonymousSessionsNeverReuse() throws Exception {
        // 匿名做一次并提交
        TestSessionRef first = createTravelSession(null);
        answerAllTravelQuestions(first, 5, null);
        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/submit", first.id())
                .with(csrf()), first)).andExpect(status().isOk());

        // 另一次匿名会话：不该"记得"上一次
        TestSessionRef second = createTravelSession(null);
        mockMvc.perform(withToken(
                        get("/api/travel/sessions/{id}/profile", second.id()), second))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("别人的画像不会被复用——跨会话复用的边界是「同一个用户」")
    void otherUsersProfileIsNeverReused() throws Exception {
        MockHttpSession alice = registerAndLogin(uniqueUsername("alice_reuse"), "Passw0rd!");
        MockHttpSession bob = registerAndLogin(uniqueUsername("bob_reuse"), "Passw0rd!");

        // Alice 测过一次
        TestSessionRef aliceSession = createTravelSession(alice);
        answerAllTravelQuestions(aliceSession, 5, alice);
        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/submit", aliceSession.id())
                .with(csrf()).session(alice), aliceSession)).andExpect(status().isOk());

        // Bob 从没测过 → 他新建的会话读不到任何画像
        TestSessionRef bobSession = createTravelSession(bob);
        mockMvc.perform(get("/api/travel/sessions/{id}/profile", bobSession.id()).session(bob))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("第一次用的人（从没测过）仍然是 404，而不是拿到一份空画像")
    void firstTimeUserStillGets404() throws Exception {
        MockHttpSession login = registerAndLogin(uniqueUsername("newbie_reuse"), "Passw0rd!");
        TestSessionRef session = createTravelSession(login);

        mockMvc.perform(get("/api/travel/sessions/{id}/profile", session.id()).session(login))
                .andExpect(status().isNotFound());
    }

    // ==========================================================
    // 顺带确认：复用不影响「人格」那条链路
    // ==========================================================

    @Test
    @DisplayName("人格测试的画像不会被旅行画像串味")
    void personalityProfilesAreNotAffected() throws Exception {
        MockHttpSession login = registerAndLogin(uniqueUsername("mixed"), "Passw0rd!");

        // 做一次人格测试并且提交
        TestSessionRef personality = createTestSession(login);
        answerAllQuestions(personality, 3, login);
        mockMvc.perform(withToken(post("/api/test-sessions/{id}/submit", personality.id())
                .with(csrf()).session(login), personality)).andExpect(status().isOk());

        // 新建一个旅行会话：不该把人格画像当成旅行画像复用过来
        TestSessionRef travel = createTravelSession(login);
        mockMvc.perform(get("/api/travel/sessions/{id}/profile", travel.id()).session(login))
                .andExpect(status().isNotFound());

        // 反过来也一样：人格的 result 接口不会去翻旅行画像
        TestSessionRef newPersonality = createTestSession(login);
        mockMvc.perform(get("/api/test-sessions/{id}/result", newPersonality.id())
                        .session(login))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("复用的画像不能跨量表：只找 TRAVEL 会话")
    void reuseOnlyLooksAtTravelSessions() throws Exception {
        MockHttpSession login = registerAndLogin(uniqueUsername("scale_mix"), "Passw0rd!");

        // 只做人格测试
        TestSessionRef personality = createTestSession(login);
        answerAllQuestions(personality, 3, login);
        mockMvc.perform(withToken(post("/api/test-sessions/{id}/submit", personality.id())
                .with(csrf()).session(login), personality)).andExpect(status().isOk());

        // 旅行会话读不到东西——这就是断言本身：
        // fallback 只在 TRAVEL 会话里找，人格画像不会被错当成旅行画像
        TestSessionRef travel = createTravelSession(login);
        mockMvc.perform(get("/api/travel/sessions/{id}/profile", travel.id()).session(login))
                .andExpect(status().isNotFound());
    }
}
