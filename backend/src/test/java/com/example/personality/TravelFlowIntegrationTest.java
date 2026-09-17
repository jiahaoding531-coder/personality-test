package com.example.personality;

import com.example.personality.entity.RecommendationBatch;
import com.example.personality.entity.QuestionScale;
import com.example.personality.repository.RecommendationBatchRepository;
import com.example.personality.repository.TestSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
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

    /** 河南平顶山，离杭州所有演示 POI 都远超默认的 10 公里半径。 */
    private static final double PINGDINGSHAN_LAT = 33.7350;
    private static final double PINGDINGSHAN_LNG = 113.3077;

    @Autowired
    private RecommendationBatchRepository batchRepository;

    @Autowired
    private TestSessionRepository sessionRepository;

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
                // ⚠️ 坐标是「导航过去」那个链接的全部依据。
                // 缺了它前端只能显示一个地名，用户还得自己开地图去搜——
                // 而"推荐出来的地方能直接去"正是这个功能的落点。
                .andExpect(jsonPath("$.places[0].latitude").isNumber())
                .andExpect(jsonPath("$.places[0].longitude").isNumber())
                // 地点坐标必须在杭州范围内（种子数据全在杭州）。
                // 这条同时在守经纬度有没有被写反——写反了纬度会是 120 左右，
                // 而那种错误前端「导航过去」才会暴露，那时已经晚了。
                .andExpect(jsonPath("$.places[0].latitude").value(lessThan(31.0)))
                .andExpect(jsonPath("$.places[0].longitude").value(greaterThan(119.0)))
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
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("同一会话并发请求推荐 → 每次都成功且批次号不重复")
    void concurrentRequestsUseDistinctBatchNumbers() throws Exception {
        TestSessionRef ref = createTravelSession(null);
        try {
            answerAllTravelQuestions(ref, 4, null);
            mockMvc.perform(withToken(post("/api/travel/sessions/{id}/submit", ref.id()).with(csrf()), ref))
                    .andExpect(status().isOk());

            String body = json(Map.of("latitude", WEST_LAKE_LAT, "longitude", WEST_LAKE_LNG));
            int requestCount = 6;
            CountDownLatch ready = new CountDownLatch(requestCount);
            CountDownLatch start = new CountDownLatch(1);

            List<CompletableFuture<Integer>> requests = IntStream.range(0, requestCount)
                    .mapToObj(ignored -> CompletableFuture.supplyAsync(() -> {
                        ready.countDown();
                        try {
                            start.await(5, TimeUnit.SECONDS);
                            MvcResult result = mockMvc.perform(withToken(
                                            post("/api/travel/sessions/{id}/recommendations", ref.id()).with(csrf())
                                                    .contentType(MediaType.APPLICATION_JSON)
                                                    .content(body), ref))
                                    .andReturn();
                            if (result.getResponse().getStatus() != 200) {
                                throw new AssertionError("并发推荐返回 " + result.getResponse().getStatus());
                            }
                            return objectMapper.readTree(result.getResponse().getContentAsByteArray())
                                    .get("batchNo").asInt();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }))
                    .toList();

            org.junit.jupiter.api.Assertions.assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<Integer> batchNumbers = requests.stream().map(CompletableFuture::join).sorted().toList();
            org.junit.jupiter.api.Assertions.assertEquals(List.of(1, 2, 3, 4, 5, 6), batchNumbers);
        } finally {
            sessionRepository.deleteById(ref.id());
        }
    }

    @Test
    @DisplayName("定位远离杭州时连续请求两次 → 都返回空结果，不写孤儿批次也不再 500")
    void emptyRecommendationsDoNotCreateOrphanBatch() throws Exception {
        TestSessionRef ref = createTravelSession(null);
        answerAllTravelQuestions(ref, 4, null);
        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/submit", ref.id()).with(csrf()), ref))
                .andExpect(status().isOk());

        String body = json(Map.of(
                "latitude", PINGDINGSHAN_LAT,
                "longitude", PINGDINGSHAN_LNG));

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(withToken(post("/api/travel/sessions/{id}/recommendations", ref.id()).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body), ref))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.batchNo").value(1))
                    .andExpect(jsonPath("$.places.length()").value(0));
        }

        // 空结果不是一批可解释、可反馈的推荐，因此不能留下批次上下文。
        org.junit.jupiter.api.Assertions.assertTrue(
                batchRepository.findFirstBySessionIdOrderByBatchNoDesc(ref.id()).isEmpty());
    }

    @Test
    @DisplayName("历史上已有孤儿批次 → 新推荐从两张表的最大批次继续，不会撞唯一约束")
    void existingOrphanBatchDoesNotBreakNextRecommendation() throws Exception {
        TestSessionRef ref = createTravelSession(null);
        answerAllTravelQuestions(ref, 4, null);
        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/submit", ref.id()).with(csrf()), ref))
                .andExpect(status().isOk());

        // 模拟 V11 缺陷已经写进生产库的状态：有第 7 批上下文，却没有第 7 批推荐。
        batchRepository.save(RecommendationBatch.of(
                ref.id(), 7, null,
                LocalTime.of(10, 0), 240, BigDecimal.TEN, null,
                Set.of(), Set.of(), Map.of(), null, null));

        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/recommendations", ref.id()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("latitude", WEST_LAKE_LAT, "longitude", WEST_LAKE_LNG))), ref))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchNo").value(8))
                .andExpect(jsonPath("$.places.length()").value(3));

        org.junit.jupiter.api.Assertions.assertTrue(
                batchRepository.findBySessionIdAndBatchNo(ref.id(), 8).isPresent());
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

    @Test
    @DisplayName("没配置 AI 时，理由接口返回 501 —— 前端据此把 AI 入口藏起来")
    void reasonsReturnNotImplementedWhenAiIsDisabled() throws Exception {
        // ⚠️ 这个测试跑在**默认上下文**里（app.ai.enabled 没设 → 走桩实现），
        // 所以它验证的是一条真实存在的路径，而不是"没配 key 会怎样"的猜测：
        // 别人 clone 这个仓库、什么都不配直接跑，推荐功能完全正常，
        // 只是没有 AI 那段话——而不是看到一个坏掉的功能或者一堆报错。
        //
        // （带假生成器的那些用例在 TravelReasonIntegrationTest 里。）
        TestSessionRef ref = createTravelSession(null);
        answerAllTravelQuestions(ref, 4, null);
        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/submit", ref.id()).with(csrf()), ref))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/recommendations", ref.id()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("latitude", WEST_LAKE_LAT, "longitude", WEST_LAKE_LNG))), ref))
                .andExpect(status().isOk());

        mockMvc.perform(withToken(
                        post("/api/travel/sessions/{id}/recommendations/reasons", ref.id()).with(csrf()),
                        ref))
                // 501 而不是 404：接口存在，只是功能没启用。前端能据此区分
                // "地址写错了"和"AI 没配"，并给出不同的提示
                .andExpect(status().isNotImplemented());
    }

    /**
     * 固定在杭州时间 10:00，让“返回哪三个地点”的断言不受本机时区和执行时刻影响。
     * 引擎会硬过滤已经关门的地点，不固定 Clock 就可能本地绿、CI 深夜红。
     */
    @TestConfiguration
    static class FixedMorningClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(
                    Instant.parse("2026-06-15T02:00:00Z"),
                    ZoneId.of("Asia/Shanghai"));
        }
    }
}
