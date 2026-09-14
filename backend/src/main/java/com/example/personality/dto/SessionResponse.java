package com.example.personality.dto;

import com.example.personality.entity.TestSession;

import java.time.Instant;
import java.util.UUID;

/**
 * 创建测试会话的响应。
 *
 * @param sessionId   会话 id。<b>注意它只是一个标识，不是凭证</b>——
 *                    自增的连续整数，没有保护价值。
 * @param accessToken <b>会话访问令牌，仅在创建时返回这一次。</b>
 *                    后续对这个会话的所有操作都要在
 *                    {@code X-Session-Token} 请求头里带上它。
 *
 *                    <p>它的作用是支持「不登录也能做测试」，同时不让别人
 *                    遍历 sessionId 就读到结果。令牌是随机 UUID，猜不到。
 *
 *                    <p>⚠️ 客户端要把它存好。它<b>不会</b>出现在历史记录等
 *                    任何列表接口里——那些场景靠登录身份鉴权。
 */
public record SessionResponse(
        Long sessionId,
        UUID accessToken,
        String status,
        Instant createdAt
) {

    public static SessionResponse from(TestSession session) {
        return new SessionResponse(
                session.getId(),
                session.getAccessToken(),
                session.getStatus().name(),
                session.getCreatedAt()
        );
    }
}
