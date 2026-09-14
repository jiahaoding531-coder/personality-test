package com.example.personality.exception;

import org.springframework.http.HttpStatus;

/**
 * 请求过于频繁，映射为 HTTP <b>429 Too Many Requests</b>。
 *
 * <p>429 是专门为限流设计的标准状态码，比 403 精确得多：
 * 403 的意思是"你没权限，别试了"，429 的意思是"你有权限，
 * 但请过一会儿再来"。客户端和监控系统能据此做正确的处理
 * （比如前端显示倒计时而不是"权限不足"）。
 *
 * <p>响应里还会带一个 {@code Retry-After} 头，告诉客户端要等多少秒。
 * 这是 HTTP 规范为此定义的标准头，比在响应体里塞一个自定义字段更通用。
 */
public class RateLimitExceededException extends BusinessException {

    /** 建议客户端等待的秒数，会被写到 Retry-After 响应头里。 */
    private final long retryAfterSeconds;

    public RateLimitExceededException(String message, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
