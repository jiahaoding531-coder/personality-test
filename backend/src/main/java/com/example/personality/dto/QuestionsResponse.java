package com.example.personality.dto;

import java.util.List;

/**
 * {@code GET /api/questions} 的响应：题库 + 量表选项一起返回。
 *
 * <p>把这两样打包在一起，前端一个请求就能把测试页渲染出来，
 * 不需要额外再请求一次量表定义。
 */
public record QuestionsResponse(
        List<ScaleOption> options,
        List<QuestionResponse> questions
) {
}
