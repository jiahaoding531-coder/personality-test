package com.example.personality.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * {@code POST /api/test-sessions/{id}/answers} 的请求体。
 *
 * <p>支持<b>批量提交</b>：一次可以传 1 道题（用户刚答完一题就自动保存），
 * 也可以传 20 道题（前端攒着最后一起交）。已经答过的题会更新而不是重复插入。
 */
public record SubmitAnswersRequest(

        @NotEmpty(message = "答案列表不能为空")
        @Valid   // 关键：没有这个注解，列表里每个元素的校验规则不会生效
        List<AnswerSubmission> answers
) {
}
