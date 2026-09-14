package com.example.personality.dto;

import java.time.Instant;

/**
 * {@code POST /api/test-sessions/{id}/ai-report} 的响应。
 *
 * @param provider   由哪个模型生成的（如 "deepseek:deepseek-chat"，或未启用时的 "stub"）。
 *                   排查问题时非常有用——能立刻确认"AI 没生效"是因为
 *                   配置没打开，还是因为模型本身返回了奇怪的东西
 * @param cached     true 表示这条是<b>之前生成过的</b>，本次直接复用、没有调用大模型。
 *                   前端据此提示"以下是上次生成的结果"，而不是让用户以为
 *                   自己点了没反应
 * @param generatedAt 这份报告的<b>首次</b>生成时间。缓存命中时它是过去的时间，
 *                    不是本次请求的时间——这样用户能看出内容的新鲜度
 */
public record AiReportResponse(
        Long sessionId,
        String content,
        String provider,
        Instant generatedAt,
        boolean cached
) {
}
