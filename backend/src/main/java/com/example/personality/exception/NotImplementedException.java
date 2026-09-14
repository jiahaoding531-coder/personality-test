package com.example.personality.exception;

import org.springframework.http.HttpStatus;

/**
 * 功能尚未实现，映射为 HTTP 501。
 *
 * <p>V0.1 专门用于 {@code POST /api/test-sessions/{id}/ai-report}。
 * 表结构（ai_reports）已经建好，接口占位也已就位，V0.2 只需要替换
 * AiReportGenerator 的实现类即可。
 */
public class NotImplementedException extends BusinessException {

    public NotImplementedException(String message) {
        super(HttpStatus.NOT_IMPLEMENTED, message);
    }
}
