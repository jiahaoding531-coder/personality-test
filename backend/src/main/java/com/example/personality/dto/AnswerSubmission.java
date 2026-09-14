package com.example.personality.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 一道题的作答。
 *
 * <p>这里的注解来自 Jakarta Bean Validation。只要 Controller 的参数上加了
 * {@code @Valid}，Spring 就会在进入方法体之前自动校验，不合法直接返回 400，
 * 你不需要在业务代码里写一堆 if 判断。
 *
 * <p>注意 {@code score} 用的是包装类型 {@code Integer} 而不是 {@code int}——
 * 因为 {@code int} 的默认值是 0，前端要是漏传了这个字段，反序列化后是 0，
 * {@code @NotNull} 检查不出来（0 不是 null），最后变成"用户打了 0 分"
 * 这种莫名其妙的错误。用 Integer 才能区分"没传"和"传了 0"。
 */
public record AnswerSubmission(

        @NotNull(message = "questionId 不能为空")
        Long questionId,

        @NotNull(message = "score 不能为空")
        @Min(value = 1, message = "score 最小为 1")
        @Max(value = 5, message = "score 最大为 5")
        Integer score
) {
}
