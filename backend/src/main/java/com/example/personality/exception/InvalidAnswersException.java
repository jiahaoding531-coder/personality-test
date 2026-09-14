package com.example.personality.exception;

import org.springframework.http.HttpStatus;

/**
 * 答案数据不合法：分值越界、缺少某个维度的作答、题目数量对不上等。
 *
 * <p>由 {@code ScoringService} 抛出，映射为 HTTP 400。
 */
public class InvalidAnswersException extends BusinessException {

    public InvalidAnswersException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
