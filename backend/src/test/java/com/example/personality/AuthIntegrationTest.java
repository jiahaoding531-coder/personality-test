package com.example.personality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证接口的集成测试。
 */
class AuthIntegrationTest extends IntegrationTestBase {

    // ==========================================================
    // 注册
    // ==========================================================

    @Test
    @DisplayName("注册成功返回 201，且响应里绝不包含密码哈希")
    void registerSucceeds() throws Exception {
        String username = uniqueUsername("reg");

        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new Credentials(username, "password123"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.createdAt").exists())
                // ⚠️ 这两条是安全断言，不是格式断言。
                // 如果哪天有人图省事把 User 实体直接返回，这两条会立刻失败。
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    @DisplayName("用户名重复返回 409")
    void duplicateUsernameRejected() throws Exception {
        String username = uniqueUsername("dup");
        var body = json(new Credentials(username, "password123"));

        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已被占用")));
    }

    @Test
    @DisplayName("用户名含非法字符返回 400，且 fieldErrors 指出是哪个字段")
    void invalidUsernameRejected() throws Exception {
        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new Credentials("bad name!@#", "password123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("username"));
    }

    @Test
    @DisplayName("密码少于 8 位返回 400")
    void shortPasswordRejected() throws Exception {
        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new Credentials(uniqueUsername("short"), "123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
    }

    // ==========================================================
    // 登录
    // ==========================================================

    @Test
    @DisplayName("登录成功返回用户信息并下发会话")
    void loginSucceeds() throws Exception {
        String username = uniqueUsername("login");
        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new Credentials(username, "password123"))))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new Credentials(username, "password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andReturn();

        assertNotNull(result.getRequest().getSession(false), "登录成功必须建立会话");
    }

    /**
     * <b>这是本文件最重要的一条测试。</b>
     *
     * <p>「密码错误」和「用户不存在」必须返回<b>完全相同</b>的状态码和消息。
     * 只要两者有一点差别，攻击者就能拿一个用户名字典逐个试，
     * 快速枚举出系统里有哪些账号——这叫用户名枚举漏洞。
     *
     * <p>这条测试的价值在于：它把"不能泄露差异"这个**安全要求**固化成了断言。
     * 将来有人为了"用户体验"把消息改成「该用户不存在，请先注册」，CI 会立刻拦住。
     */
    @Test
    @DisplayName("密码错误与用户不存在返回完全相同的响应（防用户名枚举）")
    void wrongPasswordAndUnknownUserAreIndistinguishable() throws Exception {
        String username = uniqueUsername("enum");
        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new Credentials(username, "password123"))))
                .andExpect(status().isCreated());

        // 场景 A：用户存在，密码错
        MvcResult wrongPassword = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new Credentials(username, "wrong_password"))))
                .andExpect(status().isUnauthorized())
                .andReturn();

        // 场景 B：用户根本不存在
        MvcResult unknownUser = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new Credentials("no_such_user_at_all", "whatever123"))))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertEquals(wrongPassword.getResponse().getStatus(), unknownUser.getResponse().getStatus(),
                "两种失败的状态码必须一致");

        // ⚠️ 不能直接比原始 JSON 字符串——timestamp 每次请求都不同，那样比必然失败，
        //    而且失败原因会掩盖真正要验证的东西。
        //
        // 做法是解析成 JSON 树、去掉 timestamp、再比剩下的**全部**字段。
        // 为什么不逐个字段断言？因为那样将来新增字段时会**静默漏过**；
        // 整体比对则任何新字段都被自动覆盖。
        assertEquals(withoutTimestamp(wrongPassword), withoutTimestamp(unknownUser),
                "除时间戳外，两种失败的响应体必须完全一致，否则可被用来枚举用户名");
    }

    /**
     * 复制一份响应 JSON 并去掉 timestamp，用于比对"业务上应该相同"的两个响应。
     *
     * <p>用 {@code new String(bytes, UTF_8)} 而不是 {@code getContentAsString()}，
     * 有两个原因：
     * <ol>
     *   <li>不抛受检异常，辅助方法不用跟着声明 {@code throws}</li>
     *   <li><b>显式指定 UTF-8</b>——依赖平台默认编码的话，在中文 Windows（GBK）
     *       上会把中文字符串读成乱码，于是"两个响应是否相同"的比较失去意义</li>
     * </ol>
     */
    private tools.jackson.databind.JsonNode withoutTimestamp(MvcResult result) {
        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        tools.jackson.databind.node.ObjectNode node =
                (tools.jackson.databind.node.ObjectNode) objectMapper.readTree(body);
        node.remove("timestamp");
        return node;
    }

    // ==========================================================
    // 当前用户
    // ==========================================================

    @Test
    @DisplayName("未登录时 /api/auth/me 返回 200 + 空响应体（不是 401）")
    void meReturnsEmptyWhenAnonymous() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").doesNotExist());
    }

    @Test
    @DisplayName("登录后 /api/auth/me 返回当前用户")
    void meReturnsUserWhenAuthenticated() throws Exception {
        String username = uniqueUsername("me");
        MockHttpSession session = registerAndLogin(username, "password123");

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    // ==========================================================
    // 注销
    // ==========================================================

    @Test
    @DisplayName("注销返回 204，之后 /me 恢复为空，受保护接口重新变 401")
    void logoutInvalidatesSession() throws Exception {
        String username = uniqueUsername("logout");
        MockHttpSession session = registerAndLogin(username, "password123");

        // 注销前：能拿到用户
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(jsonPath("$.username").value(username));

        mockMvc.perform(post("/api/auth/logout").with(csrf()).session(session))
                .andExpect(status().isNoContent());

        // 注销后：会话被销毁
        mockMvc.perform(get("/api/me/test-sessions").session(session))
                .andExpect(status().isUnauthorized());
    }

    // ==========================================================
    // CSRF
    // ==========================================================

    @Test
    @DisplayName("不带 CSRF 令牌的 POST 返回 403（防护确实生效）")
    void postWithoutCsrfTokenIsForbidden() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        // 注意这里**故意不加** .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new Credentials(uniqueUsername("csrf"), "password123"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET 请求不需要 CSRF 令牌")
    void getDoesNotRequireCsrfToken() throws Exception {
        mockMvc.perform(get("/api/questions")).andExpect(status().isOk());
    }

    // ==========================================================
    // 会话固定攻击防护
    // ==========================================================

    @Test
    @DisplayName("登录时会话 ID 必须更换（防会话固定攻击）")
    void sessionIdChangesOnLogin() throws Exception {
        String username = uniqueUsername("fixation");
        mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new Credentials(username, "password123"))))
                .andExpect(status().isCreated());

        // 先访问一个接口，拿到登录前的会话 ID
        MockHttpSession before = (MockHttpSession) mockMvc.perform(get("/api/auth/me"))
                .andReturn().getRequest().getSession(true);
        String idBefore = before.getId();

        // 用同一个会话登录
        MvcResult login = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .session(before)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new Credentials(username, "password123"))))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession after = (MockHttpSession) login.getRequest().getSession(false);
        assertNotNull(after);
        assertNotEquals(idBefore, after.getId(),
                "登录时必须换新的会话 ID，否则攻击者可以用事先知道的会话 ID 劫持登录后的身份");
    }
}
