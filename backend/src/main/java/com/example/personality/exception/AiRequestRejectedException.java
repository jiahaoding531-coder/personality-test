package com.example.personality.exception;

import org.springframework.http.HttpStatus;

/** 认证和调用保护失败只返回安全的错误码，不携带上游响应或凭据。 */
public class AiRequestRejectedException extends BusinessException {
    public AiRequestRejectedException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }
}
