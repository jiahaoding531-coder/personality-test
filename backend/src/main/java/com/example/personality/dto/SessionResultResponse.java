package com.example.personality.dto;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/test-sessions/{id}/result} 的响应。
 *
 * <p>这也是下一轮 AI 模块的输入——{@code TestSessionService} 会把
 * {@code dimensions} 里的这些字段组织成提示词发给大模型。
 * 所以每个字段都保留了 {@code rawScore} 和 {@code itemCount}，
 * 让 AI 知道"这个分数是几道题算出来的"，避免它说出
 * "你答了 40 道题"这种与事实不符的话。
 *
 * @param disclaimer 免责声明。计划书第五节明确要求：本测试定位为
 *                   "自我探索/娱乐性质的画像"，不能宣传成心理诊断。
 *                   把这句话放在响应里而不是只写在前端页面上，
 *                   可以保证无论哪个客户端调用都带着它。
 */
public record SessionResultResponse(
        Long sessionId,
        String status,
        Instant createdAt,
        Instant submittedAt,
        List<DimensionResult> dimensions,
        String disclaimer
) {
}
