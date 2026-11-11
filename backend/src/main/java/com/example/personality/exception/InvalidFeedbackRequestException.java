package com.example.personality.exception;

import org.springframework.http.HttpStatus;

/** 自然语言反馈请求体无法解析或未通过 Bean Validation，映射为 HTTP 400。 */
public class InvalidFeedbackRequestException extends BusinessException {

    public InvalidFeedbackRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }

    public InvalidFeedbackRequestException(String message, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, message);
        initCause(cause);
    }
}
