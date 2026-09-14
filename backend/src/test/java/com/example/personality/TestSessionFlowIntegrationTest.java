package com.example.personality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 完整测试流程的集成测试：建会话 → 答题 → 提交 → 查结果。
 *
 * <p>这一层测的是**单元测试测不到的东西**：路由是否正确、
 * 事务边界是否生效、幂等性是否真的拦住了重复提交、
 * JSON 序列化的字段名对不对。
 */
class TestSessionFlowIntegrationTest extends IntegrationTestBase {

    // ==========================================================
    // 完整链路
    // ==========================================================

    @Test
    @DisplayName("完整链路：建会话 → 答 20 题 → 提交 → 查结果，且分数算对")
    void fullFlowProducesCorrectScores() throws Exception {
        long sessionId = createSession(null);

        // 全部选 3（中间值）→ 每个维度原始分 4×3=12 → 归一化 (12-4)/16×100 = 50.00
        answerAll(sessionId, 3, null);

        mockMvc.perform(post("/api/test-sessions/{id}/submit", sessionId).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.dimensions.length()").value(5))
                // 端到端验证计分：这是单元测试已经覆盖过的公式，
                // 但这里验证的是"从 HTTP 进来、经过数据库、再回到 HTTP"整条链路没出错
                .andExpect(jsonPath("$.dimensions[0].score").value(50.00))
                .andExpect(jsonPath("$.dimensions[0].level").value("MEDIUM"))
                .andExpect(jsonPath("$.dimensions[0].levelLabel").value("中等"))
                .andExpect(jsonPath("$.disclaimer").isNotEmpty());

        // 结果接口返回同样的内容
        mockMvc.perform(get("/api/test-sessions/{id}/result", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dimensions.length()").value(5));
    }

    /**
     * <b>全选 5 分，各维度都<b>不会</b>是 100 分</b>——因为题库里有反向计分题。
     *
     * <p>反向题的规则是 {@code 有效分 = 6 - 原始分}。所以对反向题来说，
     * 答 5 反而得到 1 分。各维度的反向题数量不同（见 V2 种子脚本）：
     *
     * <pre>
     *   开放性      2 道反向  5+5+(6-5)+(6-5) = 12  → (12-4)/16×100 = 50.00
     *   外向性      2 道反向  同上                    → 50.00
     *   责任心      1 道反向  5+5+(6-5)+5    = 16  → (16-4)/16×100 = 75.00
     *   宜人性      1 道反向  同上                    → 75.00
     *   情绪稳定性  2 道反向  同上上                  → 50.00
     * </pre>
     *
     * <p>这条测试的价值在于<b>端到端验证反向计分</b>：从 HTTP 提交答案、
     * 经过数据库、再到算分返回，整条链路上反向题的翻转确实生效了。
     * 如果哪天有人把 {@code reverse_scored} 读错或者漏了翻转，
     * 这里算出来的会是清一色 100.00，测试立刻发现。
     *
     * <p>顺带说明：<b>用户通过 API 是答不出满分的</b>——反向题的标记
     * 刻意不暴露给前端（否则可以据此操纵结果），所以前端无从得知该反着选。
     */
    @Test
    @DisplayName("全选 5 分时各维度按反向题数量得出 50/50/75/75/50（端到端验证反向计分）")
    void allHighestScoresReflectReverseScoring() throws Exception {
        long sessionId = createSession(null);
        answerAll(sessionId, 5, null);

        mockMvc.perform(post("/api/test-sessions/{id}/submit", sessionId).with(csrf()))
                .andExpect(status().isOk());

        JsonNode dims = getResultJson(sessionId).get("dimensions");
        assertEquals(0, new BigDecimal("50.00").compareTo(scoreOf(dims, "OPENNESS")),
                "开放性有 2 道反向题，全选 5 应得 50.00");
        assertEquals(0, new BigDecimal("50.00").compareTo(scoreOf(dims, "EXTRAVERSION")),
                "外向性有 2 道反向题，全选 5 应得 50.00");
        assertEquals(0, new BigDecimal("75.00").compareTo(scoreOf(dims, "CONSCIENTIOUSNESS")),
                "责任心有 1 道反向题，全选 5 应得 75.00");
        assertEquals(0, new BigDecimal("75.00").compareTo(scoreOf(dims, "AGREEABLENESS")),
                "宜人性有 1 道反向题，全选 5 应得 75.00");
        assertEquals(0, new BigDecimal("50.00").compareTo(scoreOf(dims, "EMOTIONAL_STABILITY")),
                "情绪稳定性有 2 道反向题，全选 5 应得 50.00");
    }

    // ==========================================================
    // 幂等性 —— 这是最该被自动化守住的一条
    // ==========================================================

    /**
     * <b>重复提交必须被拦住。</b>
     *
     * <p>如果放过去，会算出第二份画像覆盖第一份，用户看到的分数会莫名其妙地变。
     * 后端有两道防线：会话状态检查和唯一约束。这条测试验证的是第一道
     * （能在 MockMvc 里观察到的那个）。
     */
    @Test
    @DisplayName("重复提交返回 409，且不会产生第二份画像")
    void duplicateSubmitIsRejected() throws Exception {
        long sessionId = createSession(null);
        answerAll(sessionId, 3, null);

        mockMvc.perform(post("/api/test-sessions/{id}/submit", sessionId).with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/test-sessions/{id}/submit", sessionId).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已经提交")));
    }

    @Test
    @DisplayName("提交后再改答案返回 409")
    void cannotModifyAnswersAfterSubmit() throws Exception {
        long sessionId = createSession(null);
        answerAll(sessionId, 3, null);
        mockMvc.perform(post("/api/test-sessions/{id}/submit", sessionId).with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/test-sessions/{id}/answers", sessionId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answers", List.of(Map.of("questionId", 1, "score", 5))))))
                .andExpect(status().isConflict());
    }

    // ==========================================================
    // 校验
    // ==========================================================

    @Test
    @DisplayName("没答完就提交返回 400，且消息里说明还差几题")
    void submitWithoutAnsweringAllIsRejected() throws Exception {
        long sessionId = createSession(null);

        // 只答 5 题
        List<Long> questionIds = fetchQuestionIds();
        List<Map<String, Object>> partial = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            partial.add(Map.of("questionId", questionIds.get(i), "score", 3));
        }
        mockMvc.perform(post("/api/test-sessions/{id}/answers", sessionId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answers", partial))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/test-sessions/{id}/submit", sessionId).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("15")));
    }

    @Test
    @DisplayName("分值越界返回 400，且 fieldErrors 精确指出是哪个答案")
    void outOfRangeScoreIsRejected() throws Exception {
        long sessionId = createSession(null);

        mockMvc.perform(post("/api/test-sessions/{id}/answers", sessionId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answers",
                                List.of(Map.of("questionId", 1, "score", 99))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("answers[0].score"))
                .andExpect(jsonPath("$.fieldErrors[0].message").isNotEmpty());
    }

    @Test
    @DisplayName("答案列表为空返回 400")
    void emptyAnswersRejected() throws Exception {
        long sessionId = createSession(null);

        mockMvc.perform(post("/api/test-sessions/{id}/answers", sessionId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answers", List.of()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("请求体不是合法 JSON 返回 400（不是 500）")
    void malformedJsonReturns400() throws Exception {
        long sessionId = createSession(null);

        mockMvc.perform(post("/api/test-sessions/{id}/answers", sessionId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ bad json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("路径变量不是数字返回 400（不是 500）")
    void nonNumericPathVariableReturns400() throws Exception {
        mockMvc.perform(get("/api/test-sessions/abc/result"))
                .andExpect(status().isBadRequest());
    }

    // ==========================================================
    // 不存在的情况
    // ==========================================================

    @Test
    @DisplayName("会话不存在返回 404")
    void unknownSessionReturns404() throws Exception {
        mockMvc.perform(get("/api/test-sessions/99999999/result"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("还没提交就查结果返回 404")
    void resultBeforeSubmitReturns404() throws Exception {
        long sessionId = createSession(null);
        mockMvc.perform(get("/api/test-sessions/{id}/result", sessionId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("提交一个没答任何题的会话返回 400")
    void submitEmptySessionRejected() throws Exception {
        long sessionId = createSession(null);
        mockMvc.perform(post("/api/test-sessions/{id}/submit", sessionId).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    // ==========================================================
    // 答案可修改
    // ==========================================================

    /**
     * <b>基线用 3 分而不是 1 分或 5 分。</b>
     *
     * <p>因为 3 是李克特量表的中点，而反向计分的公式是 {@code 6 - score}——
     * 对 3 来说 {@code 6-3=3}，翻转前后不变。所以"全选 3 分"时每个维度
     * 都是 {@code 4×3=12 → 50.00}，<b>与反向题的数量无关</b>，算术最干净。
     *
     * <p>起点改成 1 或 5 的话，就得先数每个维度有几道反向题才能手算期望值——
     * 测试会变得又长又容易算错（我第一版就是这么错的）。
     */
    @Test
    @DisplayName("同一题可以重复作答，以最后一次为准；只有该题所属维度受影响")
    void answersCanBeChanged() throws Exception {
        long sessionId = createSession(null);
        List<Long> ids = fetchQuestionIds();
        long firstQuestionId = ids.get(0);   // 种子数据里第 1 题属于「开放性」

        // 基线：全选 3 → 五个维度都是 50.00
        answerAll(sessionId, 3, null);

        // 只改第 1 题为 5。已答过的题会被 UPDATE 而不是重复插入，
        // savedCount 应为 1（这次只提交了一道）
        mockMvc.perform(post("/api/test-sessions/{id}/answers", sessionId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answers",
                                List.of(Map.of("questionId", firstQuestionId, "score", 5))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedCount").value(1));

        mockMvc.perform(post("/api/test-sessions/{id}/submit", sessionId).with(csrf()))
                .andExpect(status().isOk());

        JsonNode dims = getResultJson(sessionId).get("dimensions");

        // 开放性：改了的那题 5 分，其余三题仍是 3 分 → (5+3+3+3)=14 → (14-4)/16×100 = 62.50
        assertEquals(0, new BigDecimal("62.50").compareTo(scoreOf(dims, "OPENNESS")),
                "改动答案的维度应变成 62.50，实际 " + scoreOf(dims, "OPENNESS"));

        // 其余四个维度不受影响，仍是基线 50.00
        for (String key : List.of("EXTRAVERSION", "CONSCIENTIOUSNESS", "AGREEABLENESS", "EMOTIONAL_STABILITY")) {
            assertEquals(0, new BigDecimal("50.00").compareTo(scoreOf(dims, key)),
                    key + " 不该受其他维度答案改动的影响，实际 " + scoreOf(dims, key));
        }
    }

    // ==========================================================
    // 登录与匿名的区别
    // ==========================================================

    @Test
    @DisplayName("未登录也能完整走完流程（匿名测试）")
    void anonymousUserCanCompleteFlow() throws Exception {
        long sessionId = createSession(null);
        answerAll(sessionId, 3, null);

        mockMvc.perform(post("/api/test-sessions/{id}/submit", sessionId).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    @DisplayName("登录状态下建的会话同样能走完流程")
    void loggedInUserCanCompleteFlow() throws Exception {
        MockHttpSession session = registerAndLogin(uniqueUsername("flow"), "password123");

        long sessionId = createSession(session);
        answerAll(sessionId, 4, session);

        mockMvc.perform(post("/api/test-sessions/{id}/submit", sessionId).with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    // ==========================================================
    // AI 报告（测试环境未启用 → 桩实现 → 501）
    // ==========================================================

    @Test
    @DisplayName("未配置 API Key 时 AI 报告返回 501，而不是 500")
    void aiReportReturns501WhenDisabled() throws Exception {
        long sessionId = createSession(null);
        answerAll(sessionId, 3, null);
        mockMvc.perform(post("/api/test-sessions/{id}/submit", sessionId).with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/test-sessions/{id}/ai-report", sessionId).with(csrf()))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("V0.2")));
    }

    // ==========================================================
    // 辅助方法
    // ==========================================================

    /** 建一个测试会话；传 session 表示以登录身份建（会关联 user_id）。 */
    private long createSession(MockHttpSession session) throws Exception {
        var request = post("/api/test-sessions").with(csrf());
        if (session != null) {
            request = request.session(session);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(
                        new String(result.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8))
                .get("sessionId").asLong();
    }

    /** 取题库里的全部题目 ID。 */
    private List<Long> fetchQuestionIds() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/questions"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode questions = objectMapper.readTree(
                        new String(result.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8))
                .get("questions");
        List<Long> ids = new ArrayList<>();
        questions.forEach(q -> ids.add(q.get("id").asLong()));
        return ids;
    }

    /** 全部题目都答同一个分值。 */
    private void answerAll(long sessionId, int score, MockHttpSession session) throws Exception {
        List<Map<String, Object>> answers = new ArrayList<>();
        for (Long qid : fetchQuestionIds()) {
            answers.add(Map.of("questionId", qid, "score", score));
        }
        var request = post("/api/test-sessions/{id}/answers", sessionId).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("answers", answers)));
        if (session != null) {
            request = request.session(session);
        }
        mockMvc.perform(request).andExpect(status().isOk());
    }

    /** 按维度 key（如 "OPENNESS"）从结果里取出分数。 */
    private BigDecimal scoreOf(JsonNode dimensions, String key) {
        for (JsonNode dim : dimensions) {
            if (key.equals(dim.get("key").asString())) {
                return dim.get("score").decimalValue();
            }
        }
        throw new AssertionError("结果里没有维度 " + key);
    }

    /** 取结果 JSON。 */
    private JsonNode getResultJson(long sessionId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/test-sessions/{id}/result", sessionId))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(
                new String(result.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8));
    }
}
