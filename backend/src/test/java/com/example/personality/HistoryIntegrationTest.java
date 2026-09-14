package com.example.personality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
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
 * 一个人绝不能看到别人的测试记录。
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
        MockHttpSession login = registerAndLogin(uniqueUsername("newbie"), "password123");

        mockMvc.perform(get("/api/me/test-sessions").session(login))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("做完一次测试后，历史里出现一条带 5 维分数的记录")
    void completedTestAppearsInHistory() throws Exception {
        MockHttpSession login = registerAndLogin(uniqueUsername("hist"), "password123");

        TestSessionRef session = createTestSession(login);
        answerAllQuestions(session, 3, login);
        mockMvc.perform(withToken(post("/api/test-sessions/{id}/submit", session.id())
                        .with(csrf()).session(login), session))
                .andExpect(status().isOk());

        JsonNode history = fetchHistory(login);
        assertEquals(1, history.size(), "应该恰好有一条记录");
        assertEquals(session.id(), history.get(0).get("sessionId").asLong());
        assertEquals("SUBMITTED", history.get(0).get("status").asString());
        assertEquals(5, history.get(0).get("dimensions").size(), "应带 5 个维度的简要分数");
        assertEquals(0, new BigDecimal("50.00").compareTo(
                        history.get(0).get("dimensions").get(0).get("score").decimalValue()),
                "全选 3 分的基线应记到 50.00");
    }

    /**
     * 历史记录里**不该返回访问令牌**。
     *
     * <p>登录用户查自己的历史时凭身份鉴权即可，不需要令牌。
     * 把令牌塞进列表响应只会增加泄露面——比如被浏览器缓存、
     * 被日志记录、被前端存到 localStorage。
     */
    @Test
    @DisplayName("历史响应里不包含访问令牌")
    void historyDoesNotLeakAccessToken() throws Exception {
        MockHttpSession login = registerAndLogin(uniqueUsername("noLeak"), "password123");
        TestSessionRef session = createTestSession(login);
        answerAllQuestions(session, 3, login);
        mockMvc.perform(withToken(post("/api/test-sessions/{id}/submit", session.id())
                        .with(csrf()).session(login), session))
                .andExpect(status().isOk());

        JsonNode first = fetchHistory(login).get(0);
        assertEquals(false, first.has("accessToken"),
                "历史列表不该返回令牌——那个只在创建会话时给一次");
    }

    @Test
    @DisplayName("答到一半没提交的会话也会出现在历史里，但没有分数")
    void inProgressSessionAppearsWithoutDimensions() throws Exception {
        MockHttpSession login = registerAndLogin(uniqueUsername("wip"), "password123");

        TestSessionRef session = createTestSession(login);
        List<Long> ids = fetchQuestionIds();
        List<Map<String, Object>> some = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            some.add(Map.of("questionId", ids.get(i), "score", 3));
        }
        mockMvc.perform(withToken(post("/api/test-sessions/{id}/answers", session.id())
                        .with(csrf()).session(login)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answers", some))), session))
                .andExpect(status().isOk());

        JsonNode history = fetchHistory(login);
        assertEquals(1, history.size());
        assertEquals("IN_PROGRESS", history.get(0).get("status").asString());
        assertEquals(0, history.get(0).get("dimensions").size(),
                "未提交的会话不该有分数——它还没被计分过");
    }

    // ==========================================================
    // 数据隔离
    // ==========================================================

    /**
     * <b>两个用户绝不能看到彼此的历史。</b>
     *
     * <p>这类问题（IDOR，不安全的直接对象引用）是 OWASP Top 10 的常客。
     * 本项目的设计从根上规避了它——路径是 {@code /api/me/...} 而不是
     * {@code /api/users/{id}/...}，用户 id 只从服务端会话里取，
     * 客户端没有任何参数可以操控。
     */
    @Test
    @DisplayName("两个用户的历史互相隔离（防越权访问）")
    void usersCannotSeeEachOthersHistory() throws Exception {
        MockHttpSession alice = registerAndLogin(uniqueUsername("alice"), "password123");
        TestSessionRef aliceSession = createTestSession(alice);
        answerAllQuestions(aliceSession, 3, alice);
        mockMvc.perform(withToken(post("/api/test-sessions/{id}/submit", aliceSession.id())
                        .with(csrf()).session(alice), aliceSession))
                .andExpect(status().isOk());

        MockHttpSession bob = registerAndLogin(uniqueUsername("bob"), "password123");

        assertEquals(0, fetchHistory(bob).size(),
                "B 不该看到 A 的记录。若不为 0，说明查询漏了 user_id 过滤——严重的越权漏洞");
        assertEquals(1, fetchHistory(alice).size());
    }

    @Test
    @DisplayName("匿名创建的会话不会出现在任何人的历史里")
    void anonymousSessionsAreNotListed() throws Exception {
        TestSessionRef anonymous = createTestSession(null);
        answerAllQuestions(anonymous, 3, null);
        mockMvc.perform(withToken(post("/api/test-sessions/{id}/submit", anonymous.id())
                        .with(csrf()), anonymous))
                .andExpect(status().isOk());

        MockHttpSession login = registerAndLogin(uniqueUsername("after"), "password123");
        assertEquals(0, fetchHistory(login).size(), "匿名会话的 user_id 是 NULL，不该归属到任何登录用户");
    }

    // ==========================================================
    // 辅助方法
    // ==========================================================

    private JsonNode fetchHistory(MockHttpSession login) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/me/test-sessions").session(login))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(
                new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }
}
