package com.example.personality.service;

import com.example.personality.ai.AiCredentials;
import com.example.personality.ai.FeedbackPromptContext;
import com.example.personality.ai.TextFeedbackGenerator;
import com.example.personality.ai.TextFeedbackResult;
import com.example.personality.dto.TravelQuerySnapshot;
import org.springframework.stereotype.Service;

/**
 * 自然语言反馈的无事务编排。
 *
 * <p>和 Python 里把数据库读写包成两个短函数、把 HTTP 调用放在函数之间一样，
 * 这里明确分成“读事实 → 事务外调用模型 → 写事件”三段，避免模型延迟占用数据库连接。</p>
 */
@Service
public class TravelFeedbackEventService {

    private final TravelFeedbackPersistence persistence;
    private final TextFeedbackGenerator generator;

    public TravelFeedbackEventService(TravelFeedbackPersistence persistence,
                                      TextFeedbackGenerator generator) {
        this.persistence = persistence;
        this.generator = generator;
    }

    public SavedFeedbackEvent create(Long sessionId,
                                     Long recommendationId,
                                     String text,
                                     TravelQuerySnapshot query,
                                     double latitude,
                                     double longitude,
                                     AiCredentials credentials) {
        return create(sessionId,recommendationId,text,query,latitude,longitude,credentials,null);
    }

    public SavedFeedbackEvent create(Long sessionId,Long recommendationId,String text,TravelQuerySnapshot query,
            double latitude,double longitude,AiCredentials credentials,Long expectedFeedbackVersion) {
        FeedbackTargetContext target = persistence.loadTarget(
                sessionId, recommendationId, latitude, longitude, query);
        // 在网络调用前读版本；模型返回前已有新反馈时，拒绝迟到写入。
        Long expected=expectedFeedbackVersion;
        if(recommendationId!=null && expected==null) expected=persistence.reactionState(recommendationId).version();

        FeedbackPromptContext promptContext = new FeedbackPromptContext(
                text,
                target.placeName(),
                toInteger(target.distanceMeters()),
                target.suggestedCloserDistanceMeters(),
                target.ticketPrice(),
                target.suggestedBudgetMax(),
                summarize(query));

        // 这里故意不在任何事务方法内调用：模型网络耗时不能占用数据库连接。
        TextFeedbackResult parsed = generator.interpret(credentials, promptContext);
        return persistence.saveEventWithState(
                new SaveFeedbackEventCommand(sessionId, recommendationId, text, parsed,expected));
    }

    private static Integer toInteger(Double value) {
        return value == null ? null : (int) Math.round(value);
    }

    private static String summarize(TravelQuerySnapshot query) {
        if (query == null) {
            return "";
        }
        return "状态：" + query.intents() + "；偏好：" + query.preferences()
                + "；时长：" + query.durationMinutes() + " 分钟；距离上限："
                + query.maxDistanceMeters() + " 米；预算上限：" + query.budgetMax() + " 元";
    }
}
