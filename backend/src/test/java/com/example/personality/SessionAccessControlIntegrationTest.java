package com.example.personality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话访问控制的回归测试。
 *
 * <h2>它守的是什么</h2>
 *
 * <p>修复前，这些端点只靠 sessionId 判断所有权，而 id 是自增整数——
 * 遍历一遍就拿到了全站数据。实测确认过两条攻击路径：
 *
 * <pre>
 *   ① 匿名 GET  /api/test-sessions/24/result   → 200，读到别人的画像
 *   ② 匿名 POST /api/test-sessions/22/answers  → 200，写进别人未提交的会话
 * </pre>
 *
 * <p>修复方式是给每个会话发一个随机 UUID 令牌，持有令牌才能访问。
 * <b>下面每一条测试都对应上面某一种攻击，或某种绕过尝试。</b>
 *
 * <p>这类测试的价值在于：它是**唯一能防止漏洞被改回来**的东西。
 * 将来有人觉得"带令牌太麻烦"把校验去掉，CI 会立刻变红。
 */
class SessionAccessControlIntegrationTest extends IntegrationTestBase {

    private static final String TOKEN_HEADER = "X-Session-Token";

    // ==========================================================
    // 核心：修复前能成功的那两条攻击
    // ==========================================================

    /**
     * <b>攻击 ①：遍历 id 读别人的结果。</b>
     *
     * <p>修复前这里返回 200 和完整的画像 JSON。
     * 现在必须是 404——而且**不能是 403**，403 等于告诉攻击者
     * "这个会话存在，只是你没权限"，那还是能用来枚举有效 id。
     */
    @Test
    @DisplayName("匿名不能读别人已提交的结果（修复前返回 200）")
    void anonymousCannotReadOthersResult() throws Exception {
        MockHttpSession owner = registerAndLogin(uniqueUsername("victim1"), "password123");
        TestSessionRef session = createTestSession(owner);
        answerAllQuestions(session, 3, owner);
        mockMvc.perform(withToken(post("/api/test-sessions/{id}/submit", session.id())
                        .with(csrf()).session(owner), session))
                .andExpect(status().isOk());

        // 攻击者：全新的、完全匿名的请求
        mockMvc.perform(get("/api/test-sessions/{id}/result", session.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("不存在")));
    }

    /**
     * <b>攻击 ②：往别人正在做的测试里写答案。</b>
     *
     * <p>这个比读取更恶劣——它可以篡改别人还没提交的数据，
     * 让受害者拿到一份被污染的人格画像。
     */
    @Test
    @DisplayName("匿名不能往别人的会话里写答案（修复前返回 200）")
    void anonymousCannotWriteToOthersSession() throws Exception {
        MockHttpSession owner = registerAndLogin(uniqueUsername("victim2"), "password123");
        TestSessionRef session = createTestSession(owner);   // IN_PROGRESS，还没提交

        mockMvc.perform(post("/api/test-sessions/{id}/answers", session.id())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answers",
                                List.of(Map.of("questionId", 1, "score", 1))))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("匿名不能提交别人的会话")
    void anonymousCannotSubmitOthersSession() throws Exception {
        MockHttpSession owner = registerAndLogin(uniqueUsername("victim3"), "password123");
        TestSessionRef session = createTestSession(owner);
        answerAllQuestions(session, 3, owner);

        mockMvc.perform(post("/api/test-sessions/{id}/submit", session.id()).with(csrf()))
                .andExpect(status().isNotFound());
    }

    /**
     * AI 报告这个端点漏了尤其要命——**它是要花钱的**。
     * 遍历 id 就能把别人的报告跑一遍，账单算在项目主人头上。
     */
    @Test
    @DisplayName("匿名不能跑别人的 AI 报告（否则账单算你头上）")
    void anonymousCannotGenerateAiReportForOthersSession() throws Exception {
        MockHttpSession owner = registerAndLogin(uniqueUsername("victim4"), "password123");
        TestSessionRef session = createTestSession(owner);
        answerAllQuestions(session, 3, owner);
        mockMvc.perform(withToken(post("/api/test-sessions/{id}/submit", session.id())
                        .with(csrf()).session(owner), session))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/test-sessions/{id}/ai-report", session.id()).with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ==========================================================
    // 令牌的各种绕过尝试
    // ==========================================================

    @Test
    @DisplayName("令牌不正确时返回 404（猜不到就是进不去）")
    void wrongTokenIsRejected() throws Exception {
        TestSessionRef session = createTestSession(null);
        answerAllQuestions(session, 3, null);

        mockMvc.perform(get("/api/test-sessions/{id}/result", session.id())
                        .header(TOKEN_HEADER, UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    /**
     * <b>令牌格式非法时也必须是 404，不能是 400。</b>
     *
     * <p>如果格式错给 400、格式对但不存在给 404，攻击者就能靠状态码差异
     * 区分"这个令牌格式对不对"——虽然帮助有限，但任何可观察的差异
     * 都是不必要的信息泄露。
     */
    @Test
    @DisplayName("令牌格式非法时返回 404 而不是 400（不泄露格式信息）")
    void malformedTokenAlsoReturns404() throws Exception {
        TestSessionRef session = createTestSession(null);
        answerAllQuestions(session, 3, null);

        for (String bad : List.of("not-a-uuid", "12345", "'; DROP TABLE users;--", "null")) {
            mockMvc.perform(get("/api/test-sessions/{id}/result", session.id())
                            .header(TOKEN_HEADER, bad))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("不带令牌访问匿名会话返回 404")
    void noTokenForAnonymousSessionIsRejected() throws Exception {
        TestSessionRef session = createTestSession(null);
        answerAllQuestions(session, 3, null);
        mockMvc.perform(withToken(post("/api/test-sessions/{id}/submit", session.id())
                        .with(csrf()), session))
                .andExpect(status().isOk());

        // 提交后不带令牌再查一次
        mockMvc.perform(get("/api/test-sessions/{id}/result", session.id()))
                .andExpect(status().isNotFound());
    }

    /**
     * <b>一条测试里跑完整的遍历攻击，验证"一个都拿不到"。</b>
     *
     * <p>单看某一条 404 说服力不够——真正要证明的是
     * 「把 id 从 1 数到 N，一无所获」。这里造几个会话然后全部试一遍。
     */
    @Test
    @DisplayName("遍历一批 sessionId，拿不到任何不属于自己的会话")
    void enumeratingSessionIdsYieldsNothing() throws Exception {
        MockHttpSession owner = registerAndLogin(uniqueUsername("sweep"), "password123");

        // 造 3 个已提交的会话
        long[] ids = new long[3];
        for (int i = 0; i < 3; i++) {
            TestSessionRef s = createTestSession(owner);
            answerAllQuestions(s, 3, owner);
            mockMvc.perform(withToken(post("/api/test-sessions/{id}/submit", s.id())
                            .with(csrf()).session(owner), s))
                    .andExpect(status().isOk());
            ids[i] = s.id();
        }

        // 攻击者：从 1 遍历到最大 id，不带任何凭证
        long maxId = 0;
        for (long id : ids) {
            maxId = Math.max(maxId, id);
        }
        for (long id = 1; id <= maxId; id++) {
            mockMvc.perform(get("/api/test-sessions/{id}/result", id))
                    .andExpect(status().isNotFound());
        }
    }

    // ==========================================================
    // 正常路径不能被误伤
    // ==========================================================

    @Test
    @DisplayName("带正确令牌可以完整走完匿名流程")
    void correctTokenGrantsFullAccess() throws Exception {
        TestSessionRef session = createTestSession(null);

        answerAllQuestions(session, 3, null);
        mockMvc.perform(withToken(post("/api/test-sessions/{id}/submit", session.id())
                        .with(csrf()), session))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(get("/api/test-sessions/{id}/result", session.id()), session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dimensions.length()").value(5));
    }

    @Test
    @DisplayName("登录用户访问自己的会话不需要带令牌（凭身份放行）")
    void ownerDoesNotNeedToken() throws Exception {
        MockHttpSession owner = registerAndLogin(uniqueUsername("owner"), "password123");
        TestSessionRef session = createTestSession(owner);
        answerAllQuestions(session, 3, owner);

        // 注意：这里刻意**不带** X-Session-Token
        mockMvc.perform(post("/api/test-sessions/{id}/submit", session.id())
                        .with(csrf()).session(owner))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/test-sessions/{id}/result", session.id()).session(owner))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("登录用户也不能访问别人的会话")
    void loggedInUserCannotAccessOthersSession() throws Exception {
        MockHttpSession alice = registerAndLogin(uniqueUsername("alice2"), "password123");
        TestSessionRef aliceSession = createTestSession(alice);

        MockHttpSession bob = registerAndLogin(uniqueUsername("bob2"), "password123");

        // Bob 已登录，但这不是他的会话，也没令牌
        mockMvc.perform(get("/api/test-sessions/{id}/result", aliceSession.id()).session(bob))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/test-sessions/{id}/answers", aliceSession.id())
                        .with(csrf()).session(bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("answers",
                                List.of(Map.of("questionId", 1, "score", 1))))))
                .andExpect(status().isNotFound());
    }

    /**
     * 匿名会话的 userId 是 null。要确认**任何**登录用户都不能凭"我登录了"
     * 就访问它——否则等于所有登录用户共享了对全部匿名会话的访问权。
     */
    @Test
    @DisplayName("登录用户也不能访问匿名会话（user_id 为 NULL 不等于谁都能看）")
    void loggedInUserCannotAccessAnonymousSession() throws Exception {
        TestSessionRef anonymous = createTestSession(null);
        answerAllQuestions(anonymous, 3, null);
        mockMvc.perform(withToken(post("/api/test-sessions/{id}/submit", anonymous.id())
                        .with(csrf()), anonymous))
                .andExpect(status().isOk());

        MockHttpSession someone = registerAndLogin(uniqueUsername("someone"), "password123");

        mockMvc.perform(get("/api/test-sessions/{id}/result", anonymous.id()).session(someone))
                .andExpect(status().isNotFound());
    }
}
