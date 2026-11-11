package com.example.personality.service;

import com.example.personality.ai.TextFeedbackResult;

/** 保存反馈事件后的最小返回值。 */
public record SavedFeedbackEvent(Long eventId, TextFeedbackResult result,
        com.example.personality.dto.FeedbackState feedbackState) {
    public SavedFeedbackEvent(Long eventId,TextFeedbackResult result) { this(eventId,result,null); }
}
