package com.example.personality.exception;

import org.springframework.http.HttpStatus;

/** 反馈端点收到非 JSON 请求体时映射为 HTTP 415。 */
public class UnsupportedFeedbackMediaTypeException extends BusinessException {

    public UnsupportedFeedbackMediaTypeException(String message) {
        super(HttpStatus.UNSUPPORTED_MEDIA_TYPE, message);
    }
}
