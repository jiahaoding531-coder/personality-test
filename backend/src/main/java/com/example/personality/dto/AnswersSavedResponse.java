package com.example.personality.dto;

/**
 * {@code POST /api/test-sessions/{id}/answers} 的响应。
 *
 * @param savedCount 本次实际写入或更新的作答条数
 */
public record AnswersSavedResponse(
        Long sessionId,
        int savedCount
) {
}
