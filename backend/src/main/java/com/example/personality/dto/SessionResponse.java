package com.example.personality.dto;

import com.example.personality.entity.TestSession;

import java.time.Instant;

/**
 * 创建测试会话的响应。
 *
 * <p>前端拿到 {@code sessionId} 后，后续所有答题和提交都带着它。
 * 这个 ID 相当于"本次测试"的临时身份，V0.1 不做登录也够用。
 */
public record SessionResponse(
        Long sessionId,
        String status,
        Instant createdAt
) {

    public static SessionResponse from(TestSession session) {
        return new SessionResponse(
                session.getId(),
                session.getStatus().name(),
                session.getCreatedAt()
        );
    }
}
