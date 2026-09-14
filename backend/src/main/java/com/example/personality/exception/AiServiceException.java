package com.example.personality.exception;

import org.springframework.http.HttpStatus;

/**
 * 调用外部大模型服务失败：超时、连不上、认证失败、返回格式不对等。
 *
 * <p>映射为 HTTP <b>502 Bad Gateway</b>，语义是"我这个服务是好的，
 * 但我依赖的上游出问题了"。这比笼统的 500 精确得多——
 * 前端和运维看到 502 就知道要去查上游，而不是翻自己的代码。
 *
 * <p>调用方（{@code GlobalExceptionHandler}）会把它当成业务异常处理：
 * 记 WARN 日志（不是 ERROR，因为这是预期内的外部依赖故障，
 * 不该触发半夜告警），并把可读的原因返回给前端。
 */
public class AiServiceException extends BusinessException {

    public AiServiceException(String message) {
        super(HttpStatus.BAD_GATEWAY, message);
    }

    public AiServiceException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, message);
        initCause(cause);
    }
}
