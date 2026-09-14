package com.example.personality.controller;

import com.example.personality.dto.AiReportResponse;
import com.example.personality.security.AppUserPrincipal;
import com.example.personality.security.SessionAccessGuard;
import com.example.personality.service.AiReportService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 反馈接口。
 *
 * <p>V0.1 调用会返回 <b>HTTP 501 Not Implemented</b>——
 * 这是刻意的，不是没写完。整个接口链路（Controller → Service → 接口 → 桩实现）
 * 都已经真实存在并能启动，唯一缺的是 {@code StubAiReportGenerator}
 * 里那一次 HTTP 调用。
 *
 * <p>为什么要专门返回 501 而不是让接口 404？因为 404 的意思是
 * "这个地址不存在"，会让前端以为是路由写错了；501 明确表示
 * "接口存在，功能还没做"，语义准确。前端可以据此展示
 * "该功能即将上线"，而不是报一个看起来像 bug 的错误。
 */
@RestController
@RequestMapping("/api/test-sessions")
public class AiReportController {

    private final AiReportService aiReportService;
    private final SessionAccessGuard accessGuard;

    public AiReportController(AiReportService aiReportService,
                              SessionAccessGuard accessGuard) {
        this.aiReportService = aiReportService;
        this.accessGuard = accessGuard;
    }

    /**
     * {@code POST /api/test-sessions/{id}/ai-report} —— 生成 AI 个性化反馈。
     *
     * <p>用 POST 而不是 GET，是因为这个操作<b>不是幂等的</b>：
     * 每次调用都可能产生不同的文本（大模型的输出有随机性），
     * 而且它将来会写 ai_reports 表。GET 不该有副作用。
     */
    @PostMapping("/{sessionId}/ai-report")
    public AiReportResponse generateReport(
            @PathVariable Long sessionId,
            @RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
            @AuthenticationPrincipal AppUserPrincipal principal,
            // 默认 false：已经生成过就直接返回旧的，不重复消耗 token。
            // 前端加个「重新生成」按钮，带上 ?regenerate=true 就能强制重跑。
            @RequestParam(defaultValue = "false") boolean regenerate) {

        // ⚠️ 这个校验在这里尤其重要：AI 调用是要花钱的。
        // 少了它，任何人都能遍历 sessionId 把别人的 AI 报告跑一遍，
        // 账单算在你头上。
        accessGuard.requireAccess(sessionId, SessionAccessGuard.parseToken(sessionToken), principal);

        return aiReportService.generate(sessionId, regenerate);
    }
}
