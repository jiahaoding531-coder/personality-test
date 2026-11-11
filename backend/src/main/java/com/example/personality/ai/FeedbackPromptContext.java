package com.example.personality.ai;

/**
 * 服务端为一次自然语言反馈预先计算好的上下文。
 *
 * <p>这里故意只携带可安全展示给模型的业务数据，不包含 {@link AiCredentials}，
 * 因此用户的 key 不会进入提示词、日志或解析结果。</p>
 */
public record FeedbackPromptContext(
        String rawText,
        String placeName,
        Integer targetDistanceMeters,
        Integer suggestedCloserDistanceMeters,
        Integer targetTicketPrice,
        Integer suggestedBudgetMax,
        String currentQuerySummary
) {
}
