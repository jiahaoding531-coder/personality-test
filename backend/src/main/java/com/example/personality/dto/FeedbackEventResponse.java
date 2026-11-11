package com.example.personality.dto;

import com.example.personality.service.SavedFeedbackEvent;

import java.util.List;

/** 对外暴露的反馈结果；不暴露 key、提示词或模型原始响应。 */
public record FeedbackEventResponse(
        Long eventId,
        String kind,
        String reaction,
        List<InterpretResponse.Operation> operations,
        List<String> unrecognized,
        String summary,
        boolean shouldRecommend,
        boolean excludeSeen,
        FeedbackState feedbackState) {

    public static FeedbackEventResponse from(SavedFeedbackEvent saved) {
        var result = saved.result();
        return new FeedbackEventResponse(
                saved.eventId(),
                result.kind().name(),
                result.reaction() == null ? null : result.reaction().name(),
                TravelOperationMapper.toResponseOperations(result.operations()),
                result.unrecognized(),
                result.summary(),
                result.shouldRecommend(),
                result.excludeSeen(),
                saved.feedbackState());
    }
}
