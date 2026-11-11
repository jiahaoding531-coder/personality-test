package com.example.personality.ai;

import com.example.personality.entity.RecommendationFeedback;

import java.util.List;
import java.util.Set;

/** AI 对一条推荐反馈的结构化理解结果；不含任何打分或持久化行为。 */
public record TextFeedbackResult(
        TravelFeedbackKind kind,
        RecommendationFeedback.Reaction reaction,
        Set<TravelFeedbackSignal> signals,
        List<TravelIntentOperation> operations,
        List<String> unrecognized,
        String summary,
        boolean shouldRecommend,
        boolean excludeSeen
) {
    public static TextFeedbackResult empty() {
        return new TextFeedbackResult(TravelFeedbackKind.SESSION_ADJUSTMENT, null,
                Set.of(), List.of(), List.of(), "", false, false);
    }
}
