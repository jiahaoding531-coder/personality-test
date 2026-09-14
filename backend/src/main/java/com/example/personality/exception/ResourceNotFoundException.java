package com.example.personality.exception;

import org.springframework.http.HttpStatus;

/** 请求的资源不存在（会话 ID 不存在、结果尚未生成等），映射为 HTTP 404。 */
public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
