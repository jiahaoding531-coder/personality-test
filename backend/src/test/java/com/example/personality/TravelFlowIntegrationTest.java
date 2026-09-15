package com.example.personality;

import com.example.personality.entity.QuestionScale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 旅行偏好测试链路的端到端测试。
 *
 * <p>覆盖的是 TravelMind V0 的主干：建会话 → 拉旅行题 → 答 8 题 → 提交算画像
 * → 带定位拿 Top 3 推荐。和 {@code TestSessionFlowIntegrationTest} 是一对，
 * 那边守人格链路，这边守旅行链路。
 *
 * <p>走真实 HTTP（Security 过滤器、参数校验、异常处理都在）+ 真实 PostgreSQL，
 * 每个测试方法结束后 {@code @Transactional} 自动回滚。
 */
class TravelFlowIntegrationTest extends IntegrationTestBase {

    /** 杭州西湖附近的坐标。V7 灌的 59 个 POI 全在杭州，用它才有候选。 */
    private static final double WEST_LAKE_LAT = 30.2420;
    private static final double WEST_LAKE_LNG = 120.1400;

    // ==========================================================
    // 题库：?scale=TRAVEL
    // ==========================================================

    @Test
    @DisplayName("题库接口能按 scale 取旅行题，且不带参数时仍然是人格题（默认值没破坏老行为）")
    void questionsEndpointSupportsScale() throws Exception {
        mockMvc.perform(get("/api/questions").param("scale", "TRAVEL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(8))
                .andExpect(jsonPath("$.questions[0].dimensionLabel").value("自然风光"));

        // 不带参数 —— V0.1 的调用方（老前端、老测试）必须继续拿到 20 道人格题
        mockMvc.perform(get("/api/questions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(20));
    }

    // ==========================================================
    // 计分：8 道题 → 8 维画像
    // ==========================================================

    @Test
    @DisplayName("全选最高分 → 8 个维度全是 100.00")
    void allHighestScores_produceFullProfile() throws Exception {
        TestSessionRef ref = createTravelSession(null);
        answerAllTravelQuestions(ref, 5, null);

        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/submit", ref.id()).with(csrf()), ref))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scale").value("TRAVEL"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.dimensions.length()").value(8))
                // 每维 1 道题，归一化公式退化成 (score-1)*25，所以 5 分 → 100
                .andExpect(jsonPath("$.dimensions[0].key").value("NATURE"))
                .andExpect(jsonPath("$.dimensions[0].name").value("自然风光"))
                .andExpect(jsonPath("$.dimensions[0].score").value(100.00));
    }

    @Test
    @DisplayName("全选中间分 → 8 个维度全是 50.00（验证 itemCount=1 的归一化）")
    void midpointScores_produceFifty() throws Exception {
        TestSessionRef ref = createTravelSession(null);
        answerAllTravelQuestions(ref, 3, null);

        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/submit", ref.id()).with(csrf()), ref))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dimensions[0].score").value(50.00))
                .andExpect(jsonPath("$.dimensions[7].key").value("PLANNING"));
    }

    @Test
    @DisplayName("8 道题只答了一部分 → 400，且提示还差几道")
    void incompleteAnswers_areRejected() throws Exception {
        TestSessionRef ref = createTravelSession(null);
        List<Long> ids = fetchQuestionIds(QuestionScale.TRAVEL);

        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/answers", ref.id()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answers", List.of(
                                Map.of("questionId", ids.get(0), "score", 3),
                                Map.of("questionId", ids.get(1), "score", 3))))), ref))
                .andExpect(status().isOk());

        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/submit", ref.id()).with(csrf()), ref))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("还有 6 道题")));
    }

    @Test
    @DisplayName("拿人格会话去调旅行的 submit → 409，而不是算出错误的结果")
    void submittingPersonalitySessionOnTravelEndpoint_isRejected() throws Exception {
        TestSessionRef ref = createTestSession(null);   // 人格会话
        answerAllQuestions(ref, 3, null);

        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/submit", ref.id()).with(csrf()), ref))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("人格测试")));
    }

    // ==========================================================
    // 推荐：定位 → Top 3
    // ==========================================================

    @Test
    @DisplayName("带西湖坐标请求推荐 → 返回 3 条，分数降序，每条都有推荐依据")
    void recommendationsReturnTopThreeWithReasons() throws Exception {
        TestSessionRef ref = createTravelSession(null);
        // 全部答「非常同意」= 每个维度都要 → 引擎应该在所有维度上都找高分地点
        answerAllTravelQuestions(ref, 5, null);
        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/submit", ref.id()).with(csrf()), ref))
                .andExpect(status().isOk());

        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/recommendations", ref.id()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("latitude", WEST_LAKE_LAT, "longitude", WEST_LAKE_LNG))), ref))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(ref.id()))
                .andExpect(jsonPath("$.batchNo").value(1))
                .andExpect(jsonPath("$.places.length()").value(3))
                // 名次从 1 开始，且第 1 名在最前
                .andExpect(jsonPath("$.places[0].rank").value(1))
                .andExpect(jsonPath("$.places[2].rank").value(3))
                // 分数是 0~100 的整数百分比（引擎保证 score ≤ 1）
                .andExpect(jsonPath("$.places[0].scorePercent").value(greaterThanOrEqualTo(0)))
                // 距离必须算出来了（定位是必填的，所以不该是 null）
                .andExpect(jsonPath("$.places[0].distanceKm").isNumber())
                // 推荐依据：最多 3 条，每条都带维度名和双方的分值
                .andExpect(jsonPath("$.places[0].reasons.length()").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.places[0].reasons[0].dimensionLabel").isString())
                .andExpect(jsonPath("$.places[0].reasons[0].userPreference").value(100));
    }

    @Test
    @DisplayName("再请求一次推荐 → 新开一批（batchNo=2），旧的那批不删")
    void secondRequestCreatesNewBatch() throws Exception {
        TestSessionRef ref = createTravelSession(null);
        answerAllTravelQuestions(ref, 4, null);
        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/submit", ref.id()).with(csrf()), ref))
                .andExpect(status().isOk());

        String body = json(Map.of("latitude", WEST_LAKE_LAT, "longitude", WEST_LAKE_LNG));

        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/recommendations", ref.id()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body), ref))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchNo").value(1));

        // 推荐记录是 append-only 的：重新推荐是新开一批，不是删掉旧的。
        // 因为 recommendation_feedback 挂在推荐行上，删推荐会级联删掉用户反馈。
        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/recommendations", ref.id()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body), ref))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchNo").value(2));
    }

    @Test
    @DisplayName("不传定位 → 400（定位是这个接口的必填项）")
    void missingLocation_isRejected() throws Exception {
        TestSessionRef ref = createTravelSession(null);
        answerAllTravelQuestions(ref, 3, null);
        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/submit", ref.id()).with(csrf()), ref))
                .andExpect(status().isOk());

        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/recommendations", ref.id()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("remainingMinutes", 120))), ref))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    @DisplayName("还没提交（没有画像）就要推荐 → 404")
    void recommendBeforeSubmit_isNotFound() throws Exception {
        TestSessionRef ref = createTravelSession(null);
        answerAllTravelQuestions(ref, 3, null);

        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/recommendations", ref.id()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("latitude", WEST_LAKE_LAT, "longitude", WEST_LAKE_LNG))), ref))
                .andExpect(status().isNotFound());
    }

    // ==========================================================
    // 越权：旅行数据同样挂在 session_id 上
    // ==========================================================

    @Test
    @DisplayName("不带会话令牌去读别人的旅行画像 → 404（不是 403，403 会泄露 id 存在）")
    void readingSomeoneElsesProfileWithoutToken_isNotFound() throws Exception {
        TestSessionRef ref = createTravelSession(null);
        answerAllTravelQuestions(ref, 3, null);
        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/submit", ref.id()).with(csrf()), ref))
                .andExpect(status().isOk());

        // 知道 sessionId 也没用，必须有令牌
        mockMvc.perform(get("/api/travel/sessions/{id}/profile", ref.id()))
                .andExpect(status().isNotFound());

        // 带上令牌就能读
        mockMvc.perform(withToken(get("/api/travel/sessions/{id}/profile", ref.id()), ref))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dimensions.length()").value(8));
    }

    @Test
    @DisplayName("登录用户能读自己的旅行画像，却读不到别人的")
    void loggedInUserCanOnlyReadOwnProfile() throws Exception {
        MockHttpSession aliceSession = registerAndLogin(uniqueUsername("travel_alice"), "Passw0rd!");
        MockHttpSession bobSession = registerAndLogin(uniqueUsername("travel_bob"), "Passw0rd!");

        TestSessionRef aliceRef = createTravelSession(aliceSession);
        answerAllTravelQuestions(aliceRef, 4, aliceSession);
        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/submit", aliceRef.id()).with(csrf())
                .session(aliceSession), aliceRef))
                .andExpect(status().isOk());

        // 本人可以读（登录用户访问自己的会话不需要令牌）
        mockMvc.perform(get("/api/travel/sessions/{id}/profile", aliceRef.id()).session(aliceSession))
                .andExpect(status().isOk());

        // ⚠️ Bob 登录了，但这个会话的 user_id 不是他 → 404
        mockMvc.perform(get("/api/travel/sessions/{id}/profile", aliceRef.id()).session(bobSession))
                .andExpect(status().isNotFound());
    }
}
