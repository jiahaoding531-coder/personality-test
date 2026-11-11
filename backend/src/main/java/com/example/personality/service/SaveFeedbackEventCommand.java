package com.example.personality.service;

import com.example.personality.ai.TextFeedbackResult;

/** 保存已由服务端校验过的反馈结果所需的数据。 */
public record SaveFeedbackEventCommand(
        Long sessionId,
        Long recommendationId,
        String rawText,
        TextFeedbackResult result,
        Long expectedFeedbackVersion) {
    public SaveFeedbackEventCommand(Long sessionId,Long recommendationId,String rawText,TextFeedbackResult result) {
        this(sessionId,recommendationId,rawText,result,null);
    }
}
