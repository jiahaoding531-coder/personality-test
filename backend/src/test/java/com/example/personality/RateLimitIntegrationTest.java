package com.example.personality;

import com.example.personality.security.LoginRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录速率限制的集成测试。
 *
 * <p>要验证的核心是：<b>连续失败会被拦住，但正常用户不受影响。</b>
 * 后者同样重要——限流做过头会把真实用户挡在门外。
 */
class RateLimitIntegrationTest extends IntegrationTestBase {

    /** 模拟来自指定 IP 的请求。MockMvc 默认所有请求都是 127.0.0.1。 */
    private MockHttpServletRequestBuilder fromIp(MockHttpServletRequestBuilder builder, String ip) {
        return builder.with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    private MockHttpServletRequestBuilder loginRequest(String username, String password, String ip) {
        return fromIp(post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new Credentials(username, password))), ip);
    }

    /** 注册一个账号并消耗掉注册限流配额。 */
    private void register(String username, String ip) throws Exception {
        mockMvc.perform(fromIp(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new Credentials(username, "password123"))), ip))
                .andExpect(status().isCreated());
    }

    // ==========================================================
    // 核心：连续失败会被拦住
    // ==========================================================

    @Test
    @DisplayName("同一账号连续输错密码 5 次后，第 6 次返回 429")
    void repeatedFailuresForSameAccountAreBlocked() throws Exception {
        String username = uniqueUsername("brute");
        register(username, "10.0.0.1");

        // 前 5 次：正常的 401
        for (int i = 0; i < LoginRateLimiter.MAX_FAILURES_PER_USERNAME; i++) {
            mockMvc.perform(loginRequest(username, "wrong_password", "10.0.0.1"))
                    .andExpect(status().isUnauthorized());
        }

        // 第 6 次：被拦住
        mockMvc.perform(loginRequest(username, "wrong_password", "10.0.0.1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("过于频繁")));
    }

    /**
     * <b>被限流之后，即使用正确的密码也进不去。</b>
     *
     * <p>这是刻意的——否则攻击者只要在猜到密码的那一刻就能进去，
     * 限流就只是在"降低速度"而不是"阻断"。代价是存在一种
     * 「攻击者故意打错把真实用户锁在门外」的可能性（DoS），
     * 但窗口只有 15 分钟且会自动恢复，比放任暴力破解可接受得多。
     */
    @Test
    @DisplayName("被限流后即使密码正确也返回 429（限流是真的阻断，不是减速）")
    void correctPasswordIsAlsoBlockedWhileRateLimited() throws Exception {
        String username = uniqueUsername("locked");
        register(username, "10.0.0.2");

        for (int i = 0; i < LoginRateLimiter.MAX_FAILURES_PER_USERNAME; i++) {
            mockMvc.perform(loginRequest(username, "wrong_password", "10.0.0.2"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(loginRequest(username, "password123", "10.0.0.2"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("429 响应带 Retry-After 头，告诉客户端要等多久")
    void rateLimitedResponseCarriesRetryAfterHeader() throws Exception {
        String username = uniqueUsername("retry");
        register(username, "10.0.0.3");

        for (int i = 0; i < LoginRateLimiter.MAX_FAILURES_PER_USERNAME; i++) {
            mockMvc.perform(loginRequest(username, "wrong_password", "10.0.0.3"));
        }

        mockMvc.perform(loginRequest(username, "wrong_password", "10.0.0.3"))
                .andExpect(status().isTooManyRequests())
                // Retry-After 是 HTTP 为 429 定义的标准头。
                // 值是秒数，应该在 1 到窗口长度之间。
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Retry-After",
                        org.hamcrest.Matchers.matchesPattern("[1-9][0-9]*")));
    }

    // ==========================================================
    // 不能误伤：限流必须按 key 隔离
    // ==========================================================

    @Test
    @DisplayName("一个账号被限流，不影响其他账号登录（限流按账号隔离）")
    void limitingOneAccountDoesNotAffectOthers() throws Exception {
        String victim = uniqueUsername("victim");
        String normal = uniqueUsername("normal");
        register(victim, "10.0.0.4");
        register(normal, "10.0.0.5");

        // 把 victim 打到限流
        for (int i = 0; i < LoginRateLimiter.MAX_FAILURES_PER_USERNAME + 1; i++) {
            mockMvc.perform(loginRequest(victim, "wrong_password", "10.0.0.4"));
        }
        mockMvc.perform(loginRequest(victim, "wrong_password", "10.0.0.4"))
                .andExpect(status().isTooManyRequests());

        // normal 从**另一个 IP** 登录，完全不受影响
        mockMvc.perform(loginRequest(normal, "password123", "10.0.0.5"))
                .andExpect(status().isOk());
    }

    /**
     * 换个 IP 还是要输入正确密码——这条验证的是"用户名维度的限流
     * 不会被换 IP 绕过"。
     *
     * <p>攻击者可以换代理 IP，但他要打的账号是固定的。
     * 所以用户名那一套限流必须独立于 IP 生效。
     */
    @Test
    @DisplayName("换 IP 也不能绕过针对某账号的限流（用户名维度独立生效）")
    void usernameLimitCannotBeBypassedByChangingIp() throws Exception {
        String username = uniqueUsername("crossip");
        register(username, "10.0.1.1");

        // 每个 IP 只失败一次——单看 IP 维度完全没超限
        for (int i = 0; i < LoginRateLimiter.MAX_FAILURES_PER_USERNAME; i++) {
            mockMvc.perform(loginRequest(username, "wrong_password", "10.0.1." + (i + 10)))
                    .andExpect(status().isUnauthorized());
        }

        // 第 6 次换了新 IP，但用户名维度已经超限 → 依然被拦
        mockMvc.perform(loginRequest(username, "wrong_password", "10.0.1.99"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("用同一个 IP 扫大量不同账号，会被 IP 维度拦住")
    void scanningManyAccountsFromOneIpIsBlocked() throws Exception {
        String ip = "10.0.2.1";

        // 每个用户名都是新的（用户名维度不会触发），但都来自同一个 IP
        for (int i = 0; i < LoginRateLimiter.MAX_FAILURES_PER_IP; i++) {
            mockMvc.perform(loginRequest("nonexistent_" + i, "whatever123", ip))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(loginRequest("nonexistent_final", "whatever123", ip))
                .andExpect(status().isTooManyRequests());
    }

    // ==========================================================
    // 正常用户不该被误伤
    // ==========================================================

    @Test
    @DisplayName("登录成功后清除该账号的失败计数（打错几次后想起来密码仍能登录）")
    void successfulLoginResetsFailureCount() throws Exception {
        String username = uniqueUsername("reset");
        String ip = "10.0.3.1";
        register(username, ip);

        // 先打错 4 次（没到 5 次的阈值）
        for (int i = 0; i < LoginRateLimiter.MAX_FAILURES_PER_USERNAME - 1; i++) {
            mockMvc.perform(loginRequest(username, "wrong_password", ip))
                    .andExpect(status().isUnauthorized());
        }

        // 第 5 次输对了 → 成功，且计数被清零
        mockMvc.perform(loginRequest(username, "password123", ip))
                .andExpect(status().isOk());

        // 之后又能重新失败 4 次而不被拦（说明计数确实清零了）
        for (int i = 0; i < LoginRateLimiter.MAX_FAILURES_PER_USERNAME - 1; i++) {
            mockMvc.perform(loginRequest(username, "wrong_password", ip))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("少于阈值的失败次数不影响正常登录")
    void aFewFailuresDoNotBlockNormalLogin() throws Exception {
        String username = uniqueUsername("few");
        String ip = "10.0.3.2";
        register(username, ip);

        mockMvc.perform(loginRequest(username, "typo1", ip)).andExpect(status().isUnauthorized());
        mockMvc.perform(loginRequest(username, "typo2", ip)).andExpect(status().isUnauthorized());

        mockMvc.perform(loginRequest(username, "password123", ip)).andExpect(status().isOk());
    }

    // ==========================================================
    // 注册限流
    // ==========================================================

    @Test
    @DisplayName("同一 IP 注册超过 10 次被拦住")
    void registrationFromOneIpIsLimited() throws Exception {
        String ip = "10.0.4.1";

        for (int i = 0; i < LoginRateLimiter.MAX_REGISTRATIONS_PER_IP; i++) {
            mockMvc.perform(fromIp(post("/api/auth/register")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new Credentials(uniqueUsername("mass" + i), "password123"))), ip))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(fromIp(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new Credentials(uniqueUsername("overflow"), "password123"))), ip))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("注册失败也计数（防止用重复用户名免费探测）")
    void failedRegistrationAlsoCounts() throws Exception {
        String ip = "10.0.4.2";
        String username = uniqueUsername("dup");

        // 第一次成功
        mockMvc.perform(fromIp(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new Credentials(username, "password123"))), ip))
                .andExpect(status().isCreated());

        // 后面 9 次都因为用户名重复而 409，但依然消耗配额
        for (int i = 0; i < LoginRateLimiter.MAX_REGISTRATIONS_PER_IP - 1; i++) {
            mockMvc.perform(fromIp(post("/api/auth/register").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new Credentials(username, "password123"))), ip))
                    .andExpect(status().isConflict());
        }

        // 第 11 次：被限流拦住（而不是 409）
        mockMvc.perform(fromIp(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new Credentials(uniqueUsername("blocked"), "password123"))), ip))
                .andExpect(status().isTooManyRequests());
    }
}
