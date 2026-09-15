package com.example.personality.controller;

import com.example.personality.ai.AiCredentials;
import com.example.personality.ai.AiCredentialsResolver;
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
 * <h2>501 的含义</h2>
 *
 * <p>调用返回 <b>HTTP 501</b> 当且仅当<b>没有任何可用的 AI 凭据</b>：
 * 这台服务器没配 key，访客也没带自己的。
 *
 * <p>⚠️ <b>前端对这个 501 的处理和以前正好相反。</b>
 * 以前 501 = "这站没有 AI" → 把入口藏起来；
 * 现在 501 = "这站没替你配 AI，但**你可以填自己的**" → 引导访客去填 key。
 *
 * <p>为什么要专门返回 501 而不是让接口 404？因为 404 的意思是
 * "这个地址不存在"，会让前端以为是路由写错了；501 明确表示
 * "接口在，只是这台部署没提供这项能力"，前端能据此给出准确的话术。
 */
@RestController
@RequestMapping("/api/test-sessions")
public class AiReportController {

    private final AiReportService aiReportService;
    private final AiCredentialsResolver aiCredentialsResolver;
    private final SessionAccessGuard accessGuard;

    public AiReportController(AiReportService aiReportService,
                              AiCredentialsResolver aiCredentialsResolver,
                              SessionAccessGuard accessGuard) {
        this.aiReportService = aiReportService;
        this.aiCredentialsResolver = aiCredentialsResolver;
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
            @RequestParam(defaultValue = "false") boolean regenerate,
            // 访客自带的大模型凭据。两个都不传就是走服务端配置的那把。
            @RequestHeader(value = AiCredentialsResolver.PROVIDER_HEADER, required = false)
            String aiProvider,
            @RequestHeader(value = AiCredentialsResolver.KEY_HEADER, required = false)
            String aiKey) {

        // ⚠️ 这个校验在这里尤其重要：AI 调用是要花钱的。
        // 少了它，任何人都能遍历 sessionId 把别人的 AI 报告跑一遍。
        //
        // （接入"访客自带 key"之后，"花谁的钱"变了——花的是调用者自己的；
        //   但这条校验仍然要留：它守的是**别人的会话内容**，与会话的归属有关，
        //   和谁付费无关。）
        accessGuard.requireAccess(sessionId, SessionAccessGuard.parseToken(sessionToken), principal);

        // 都没有凭据时这里就抛 501，不会白读一遍库
        AiCredentials credentials = aiCredentialsResolver.require(aiProvider, aiKey);

        return aiReportService.generate(sessionId, regenerate, credentials);
    }
}
