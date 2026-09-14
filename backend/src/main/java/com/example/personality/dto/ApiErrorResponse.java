package com.example.personality.dto;

import java.time.Instant;
import java.util.List;

/**
 * 统一的错误响应格式。
 *
 * <p>无论哪里出错（参数校验失败、找不到资源、状态冲突、服务器内部异常），
 * 返回给前端的 JSON 结构都是这一个形状。前端因此可以写<b>一处</b>
 * 统一的错误处理逻辑，不用为每种错误猜格式。
 *
 * <p>这也是为什么需要 {@code GlobalExceptionHandler}——没有它的话，
 * Spring 默认的报错格式五花八门：参数校验失败是一种、404 是另一种、
 * 未捕获异常又是另一种（还带一大坨堆栈信息，既泄露内部结构又不美观）。
 *
 * @param fieldErrors 仅在参数校验失败时非空，逐字段列出哪里不合法
 */
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldError> fieldErrors
) {

    /** 单个字段的校验失败信息。 */
    public record FieldError(String field, String message) {
    }

    public static ApiErrorResponse of(int status, String error, String message, String path) {
        return new ApiErrorResponse(Instant.now(), status, error, message, path, List.of());
    }

    public static ApiErrorResponse withFieldErrors(int status, String error, String message,
                                                   String path, List<FieldError> fieldErrors) {
        return new ApiErrorResponse(Instant.now(), status, error, message, path, fieldErrors);
    }
}
