package com.example.personality.exception;

import org.springframework.http.HttpStatus;

/**
 * 请求与当前状态冲突，映射为 HTTP 409。
 *
 * <p>本项目里最重要的用途是<b>保证 submit 的幂等性</b>：
 * 已提交（status = SUBMITTED）的会话不能重复提交，否则会覆盖掉已有的画像。
 */
public class ConflictException extends BusinessException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
