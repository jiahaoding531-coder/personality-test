package com.example.personality.controller;

import com.example.personality.dto.SessionSummaryResponse;
import com.example.personality.security.AppUserPrincipal;
import com.example.personality.service.HistoryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 「我的」数据接口。
 *
 * <p>整个 {@code /api/me/**} 路径在 {@code SecurityConfig} 里被设为
 * {@code authenticated()}，所以未登录访问会返回 401
 * （由那里配置的 {@code authenticationEntryPoint} 产出 JSON，
 * 而不是默认的 302 重定向到登录页）。
 *
 * <p><b>为什么用 {@code /api/me/...} 而不是 {@code /api/users/{id}/...}？</b>
 * 后者存在一个经典漏洞：如果服务端不校验"路径里的 id 是不是当前登录用户"，
 * 用户只要把 URL 里的 id 改一改，就能看到别人的数据——
 * 这叫 <b>IDOR（不安全的直接对象引用）</b>，是 OWASP Top 10 里的常客。
 *
 * <p>用 {@code /me} 从 URL 里彻底消除了这个参数，也就消除了整类漏洞。
 * <b>能不给的参数就不要给。</b>
 */
@RestController
@RequestMapping("/api/me")
public class HistoryController {

    private final HistoryService historyService;

    public HistoryController(HistoryService historyService) {
        this.historyService = historyService;
    }

    /**
     * {@code GET /api/me/test-sessions} —— 当前用户的测试历史。
     *
     * <p>用户 id 直接从 principal 上取（登录时就已经装进去了），
     * 不需要再查一次数据库，也不需要客户端传任何参数。
     *
     * <p>因为路径被 {@code authenticated()} 保护，能进到这个方法的一定是
     * 已登录用户，所以 {@code principal} 不可能为 null——
     * 这也是把授权规则交给框架的好处：方法体内不用写防御性判空。
     */
    @GetMapping("/test-sessions")
    public List<SessionSummaryResponse> listMySessions(
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return historyService.listForUser(principal.getId());
    }
}
