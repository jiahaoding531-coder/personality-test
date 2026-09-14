package com.example.personality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 测试历史的集成测试。
 *
 * <p>这里最重要的不是"能不能返回列表"，而是<b>用户之间的数据隔离</b>——
 * 一个人绝不能看到别人的测试记录。这类越权问题是安全测试的核心。
 */
class HistoryIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("未登录访问历史返回 401")
    void anonymousCannotAccessHistory() throws Exception {
        mockMvc.perform(get("/api/me/test-sessions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("新用户的历史是空列表（不是 404、不是 null）")
    void newUserHasEmptyHistory() throws Exception {
        MockHttpSession session = registerAndLogin(uniqueUsername("newbie"), "password123");

        mockMvc.perform(get("/api/me/test-sessions").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("做完一次测试后，历史里出现一条带 5 维分数的记录")
    void completedTestAppearsInHistory() throws Exception {
        MockHttpSession session = registerAndLogin(uniqueUsername("hist"), "password123");

        long sessionId = createSession(session);
        answerAll(sessionId, 3, session);
        mockMvc.perform(post("/api/test-sessions/{id}/submit", sessionId).with(csrf()).session(session))
                .andExpect(status().isOk());

        JsonNode history = fetchHistory(session);
        assertEquals(1, history.size(), "应该恰好有一条记录");
        assertEquals(sessionId, history.get(0).get("sessionId").asLong());
        assertEquals("SUBMITTED", history.get(0).get("status").asString());
        assertEquals(5, history.get(0).get("dimensions").size(), "应带 5 个维度的简要分数");
        assertEquals(0, new java.math.BigDecimal("50.00")
                        .compareTo(history.get(0).get("dimensions").get(0).get("score").decimalValue()),
                "全选 3 分的基线应记到 50.00");
    }

    @Test
    @DisplayName("答到一半没提交的会话也会出现在历史里，但没有分数")
    void inProgressSessionAppearsWithoutDimensions() throws Exception {
        MockHttpSession session = registerAndLogin(uniqueUsername("wip"), "password123");

        long sessionId = createSession(session);
        // 只答几题，不提交
        List<Long> ids = fetchQuestionIds();
        List<Map<String, Object>> some = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            some.add(Map.of("questionId", ids.get(i), "score", 3));
        }
        mockMvc.perform(post("/api/test-sessions/{id}/answers", sessionId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answers", some))))
                .andExpect(status().isOk());

        JsonNode history = fetchHistory(session);
        assertEquals(1, history.size());
        assertEquals("IN_PROGRESS", history.get(0).get("status").asString());
        // 还没计分，所以 dimensions 是空数组。前端据此显示"未完成"。
        assertEquals(0, history.get(0).get("dimensions").size(),
                "未提交的会话不该有分数——它还没被计分过");
    }

    // ==========================================================
    // 数据隔离 —— 本文件最该守住的东西
    // ==========================================================

    /**
     * <b>两个用户绝不能看到彼此的历史。</b>
     *
     * <p>这类问题（IDOR，不安全的直接对象引用）是 OWASP Top 10 的常客：
     * 接口路径里带用户标识、或者查询时忘了加 {@code WHERE user_id = ?}，
     * 就会导致一个人能看到全站所有人的数据。
     *
     * <p>本项目的设计从根上规避了它——路径是 {@code /api/me/...} 而不是
     * {@code /api/users/{id}/...}，用户 id 只从服务端会话里取，
     * 客户端没有任何参数可以操控。这条测试把该设计固化下来。
     */
    @Test
    @DisplayName("两个用户的历史互相隔离（防越权访问）")
    void usersCannotSeeEachOthersHistory() throws Exception {
        // 用户 A 做一次测试
        MockHttpSession sessionA = registerAndLogin(uniqueUsername("alice"), "password123");
        long sessionAId = createSession(sessionA);
        answerAll(sessionAId, 3, sessionA);
        mockMvc.perform(post("/api/test-sessions/{id}/submit", sessionAId).with(csrf()).session(sessionA))
                .andExpect(status().isOk());

        // 用户 B 是全新的，历史必须为空
        MockHttpSession sessionB = registerAndLogin(uniqueUsername("bob"), "password123");

        JsonNode historyB = fetchHistory(sessionB);
        assertEquals(0, historyB.size(),
                "B 不该看到 A 的记录。如果这里不是 0，说明查询漏了 user_id 过滤——严重的越权漏洞");

        // 反过来，A 的历史里只有自己的那条
        JsonNode historyA = fetchHistory(sessionA);
        assertEquals(1, historyA.size());
        assertEquals(sessionAId, historyA.get(0).get("sessionId").asLong());
    }

    @Test
    @DisplayName("匿名创建的会话不会出现在任何人的历史里")
    void anonymousSessionsAreNotListed() throws Exception {
        // 先以匿名身份做一次完整测试
        long anonymousSessionId = createSession(null);
        answerAll(anonymousSessionId, 3, null);
        mockMvc.perform(post("/api/test-sessions/{id}/submit", anonymousSessionId).with(csrf()))
                .andExpect(status().isOk());

        // 登录用户的历史里不该有它（它的 user_id 是 NULL）
        MockHttpSession session = registerAndLogin(uniqueUsername("after"), "password123");
        JsonNode history = fetchHistory(session);

        for (JsonNode item : history) {
            assertEquals(false, item.get("sessionId").asLong() == anonymousSessionId,
                    "匿名会话不该归属到任何登录用户");
        }
        assertEquals(0, history.size(), "新用户的历史应为空");
    }

    // ==========================================================
    // 辅助方法
    // ==========================================================

    /**
     * 建一个测试会话。{@code session} 传 null 表示以匿名身份建。
     *
     * <p>注意不能无条件写 {@code .session(session)}——MockMvc 对 null 会直接抛
     * {@code 'session' must not be null}，而不是把它当成"不带会话"。
     */
    private long createSession(MockHttpSession session) throws Exception {
        var request = post("/api/test-sessions").with(csrf());
        if (session != null) {
            request = request.session(session);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(
                new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
                .get("sessionId").asLong();
    }

    private List<Long> fetchQuestionIds() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/questions")).andExpect(status().isOk()).andReturn();
        JsonNode questions = objectMapper.readTree(
                new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
                .get("questions");
        List<Long> ids = new ArrayList<>();
        questions.forEach(q -> ids.add(q.get("id").asLong()));
        return ids;
    }

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

    private JsonNode fetchHistory(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/me/test-sessions").session(session))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(
                new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }
}
