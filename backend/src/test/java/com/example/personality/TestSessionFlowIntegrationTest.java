package com.example.personality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 *
 * <p>修复 IDOR 之后，所有会话操作都要带 {@code X-Session-Token}。
 * 下面的辅助方法把这件事封装掉了，测试体本身不用关心。
 */
class TestSessionFlowIntegrationTest extends IntegrationTestBase {

    /** 提交并计分。返回 ResultActions 让调用方自己断言。 */
    private ResultActions submitRequest(TestSessionRef ref) throws Exception {
        return mockMvc.perform(withToken(
                post("/api/test-sessions/{id}/submit", ref.id()).with(csrf()), ref));
    }

    /** 提交答案。 */
    private ResultActions answerRequest(TestSessionRef ref, Object body) throws Exception {
        return mockMvc.perform(withToken(
                post("/api/test-sessions/{id}/answers", ref.id()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)), ref));
    }

    /** 查结果。 */
    private ResultActions resultRequest(TestSessionRef ref) throws Exception {
        return mockMvc.perform(withToken(
                get("/api/test-sessions/{id}/result", ref.id()), ref));
    }

    // ==========================================================
    // 完整链路
    // ==========================================================

    @Test
    @DisplayName("完整链路：建会话 → 答 20 题 → 提交 → 查结果，且分数算对")
    void fullFlowProducesCorrectScores() throws Exception {
        TestSessionRef session = createTestSession(null);
        answerAllQuestions(session, 3, null);

        submitRequest(session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.dimensions.length()").value(5))
                // 端到端验证计分：这个公式单元测试已经覆盖过，
                // 但这里验证的是"从 HTTP 进来、经过数据库、再回到 HTTP"整条链路没出错
                .andExpect(jsonPath("$.dimensions[0].score").value(50.00))
                .andExpect(jsonPath("$.dimensions[0].level").value("MEDIUM"))
                .andExpect(jsonPath("$.dimensions[0].levelLabel").value("中等"))
                .andExpect(jsonPath("$.disclaimer").isNotEmpty());

        resultRequest(session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dimensions.length()").value(5));
    }

    /**
     * <b>全选 5 分，各维度都<b>不会</b>是 100 分</b>——因为题库里有反向计分题。
     *
     * <p>反向题的规则是 {@code 有效分 = 6 - 原始分}，所以对反向题来说答 5 反而得 1 分。
     * 各维度的反向题数量不同（见 V2 种子脚本）：
     *
     * <pre>
     *   开放性      2 道反向  5+5+(6-5)+(6-5) = 12  → (12-4)/16×100 = 50.00
     *   外向性      2 道反向  同上                    → 50.00
     *   责任心      1 道反向  5+5+(6-5)+5    = 16  → 75.00
     *   宜人性      1 道反向  同上                    → 75.00
     *   情绪稳定性  2 道反向  同上上                  → 50.00
     * </pre>
     *
     * <p>这条测试端到端验证反向计分：从 HTTP 提交答案、经过数据库、
     * 再到算分返回，整条链路上反转确实生效了。
     *
     * <p>顺带说明：<b>用户通过 API 是答不出满分的</b>——反向题的标记
     * 刻意不暴露给前端（否则可以据此操纵结果）。
     */
    @Test
    @DisplayName("全选 5 分时各维度按反向题数量得出 50/50/75/75/50（端到端验证反向计分）")
    void allHighestScoresReflectReverseScoring() throws Exception {
        TestSessionRef session = createTestSession(null);
        answerAllQuestions(session, 5, null);
        submitRequest(session).andExpect(status().isOk());

        JsonNode dims = fetchResultJson(session).get("dimensions");
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
    // 幂等性
    // ==========================================================

    /**
     * <b>重复提交必须被拦住。</b>
     *
     * <p>如果放过去，会算出第二份画像覆盖第一份，用户看到的分数会莫名其妙地变。
     * 后端有两道防线：会话状态检查和唯一约束。这条测试验证的是第一道。
     */
    @Test
    @DisplayName("重复提交返回 409，且不会产生第二份画像")
    void duplicateSubmitIsRejected() throws Exception {
        TestSessionRef session = createTestSession(null);
        answerAllQuestions(session, 3, null);
        submitRequest(session).andExpect(status().isOk());

        submitRequest(session)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("已经提交")));
    }

    @Test
    @DisplayName("提交后再改答案返回 409")
    void cannotModifyAnswersAfterSubmit() throws Exception {
        TestSessionRef session = createTestSession(null);
        answerAllQuestions(session, 3, null);
        submitRequest(session).andExpect(status().isOk());

        answerRequest(session, Map.of("answers", List.of(Map.of("questionId", 1, "score", 5))))
                .andExpect(status().isConflict());
    }

    // ==========================================================
    // 校验
    // ==========================================================

    @Test
    @DisplayName("没答完就提交返回 400，且消息里说明还差几题")
    void submitWithoutAnsweringAllIsRejected() throws Exception {
        TestSessionRef session = createTestSession(null);

        List<Long> questionIds = fetchQuestionIds();
        List<Map<String, Object>> partial = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            partial.add(Map.of("questionId", questionIds.get(i), "score", 3));
        }
        answerRequest(session, Map.of("answers", partial)).andExpect(status().isOk());

        submitRequest(session)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("15")));
    }

    @Test
    @DisplayName("分值越界返回 400，且 fieldErrors 精确指出是哪个答案")
    void outOfRangeScoreIsRejected() throws Exception {
        TestSessionRef session = createTestSession(null);

        answerRequest(session, Map.of("answers", List.of(Map.of("questionId", 1, "score", 99))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("answers[0].score"))
                .andExpect(jsonPath("$.fieldErrors[0].message").isNotEmpty());
    }

    @Test
    @DisplayName("答案列表为空返回 400")
    void emptyAnswersRejected() throws Exception {
        TestSessionRef session = createTestSession(null);
        answerRequest(session, Map.of("answers", List.of())).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("请求体不是合法 JSON 返回 400（不是 500）")
    void malformedJsonReturns400() throws Exception {
        TestSessionRef session = createTestSession(null);
        mockMvc.perform(withToken(post("/api/test-sessions/{id}/answers", session.id()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ bad json"), session))
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
        // 不存在的 id 本身就不该被访问到；带上任意令牌也一样是 404
        mockMvc.perform(get("/api/test-sessions/99999999/result"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("还没提交就查结果返回 404")
    void resultBeforeSubmitReturns404() throws Exception {
        TestSessionRef session = createTestSession(null);
        resultRequest(session).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("提交一个没答任何题的会话返回 400")
    void submitEmptySessionRejected() throws Exception {
        TestSessionRef session = createTestSession(null);
        submitRequest(session).andExpect(status().isBadRequest());
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
     */
    @Test
    @DisplayName("同一题可以重复作答，以最后一次为准；只有该题所属维度受影响")
    void answersCanBeChanged() throws Exception {
        TestSessionRef session = createTestSession(null);
        long firstQuestionId = fetchQuestionIds().get(0);   // 种子数据里第 1 题属于「开放性」

        answerAllQuestions(session, 3, null);   // 基线：五个维度都是 50.00

        // 只改第 1 题为 5。已答过的题会被 UPDATE 而不是重复插入
        answerRequest(session, Map.of("answers",
                List.of(Map.of("questionId", firstQuestionId, "score", 5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedCount").value(1));

        submitRequest(session).andExpect(status().isOk());

        JsonNode dims = fetchResultJson(session).get("dimensions");
        // 开放性：改了的那题 5 分，其余三题仍是 3 分 → (5+3+3+3)=14 → 62.50
        assertEquals(0, new BigDecimal("62.50").compareTo(scoreOf(dims, "OPENNESS")),
                "改动答案的维度应变成 62.50，实际 " + scoreOf(dims, "OPENNESS"));

        for (String key : List.of("EXTRAVERSION", "CONSCIENTIOUSNESS",
                "AGREEABLENESS", "EMOTIONAL_STABILITY")) {
            assertEquals(0, new BigDecimal("50.00").compareTo(scoreOf(dims, key)),
                    key + " 不该受其他维度答案改动的影响");
        }
    }

    // ==========================================================
    // 登录与匿名的区别
    // ==========================================================

    @Test
    @DisplayName("未登录也能完整走完流程（匿名测试）")
    void anonymousUserCanCompleteFlow() throws Exception {
        TestSessionRef session = createTestSession(null);
        answerAllQuestions(session, 3, null);

        submitRequest(session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    @DisplayName("登录状态下建的会话同样能走完流程")
    void loggedInUserCanCompleteFlow() throws Exception {
        MockHttpSession login = registerAndLogin(uniqueUsername("flow"), "password123");

        TestSessionRef session = createTestSession(login);
        answerAllQuestions(session, 4, login);

        mockMvc.perform(withToken(post("/api/test-sessions/{id}/submit", session.id())
                        .with(csrf()).session(login), session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    // ==========================================================
    // AI 报告
    // ==========================================================

    @Test
    @DisplayName("未配置 API Key 时 AI 报告返回 501，而不是 500")
    void aiReportReturns501WhenDisabled() throws Exception {
        TestSessionRef session = createTestSession(null);
        answerAllQuestions(session, 3, null);
        submitRequest(session).andExpect(status().isOk());

        // ⚠️ 这段文案不是随手写的，它是**用户真的会看到的那句话**。
        //
        // 以前 501 的意思是"这站没有 AI"，文案是"将在 V0.2 实现"——
        // 用户看到就走了，因为那是一句绝路。
        //
        // 接入访客自带 key 之后，同样一个 501 的含义变成了
        // "这站没**替你**配 AI，但**你可以填自己的**"。所以文案必须
        // 说出那条路，而且要说清 key 存在哪（否则没人敢把凭证粘进来）。
        //
        // 断言这几件事，是为了防止有人日后顺手把它改回一句"功能未实现"——
        // 那一改，这个功能的全部意义就没了，而且不会有任何报错。
        mockMvc.perform(withToken(post("/api/test-sessions/{id}/ai-report", session.id())
                        .with(csrf()), session))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("自己的")))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("浏览器")));
    }

    // ==========================================================
    // 辅助方法
    // ==========================================================

    /** 按维度 key（如 "OPENNESS"）从结果里取出分数。 */
    private BigDecimal scoreOf(JsonNode dimensions, String key) {
        for (JsonNode dim : dimensions) {
            if (key.equals(dim.get("key").asString())) {
                return dim.get("score").decimalValue();
            }
        }
        throw new AssertionError("结果里没有维度 " + key);
    }
}
