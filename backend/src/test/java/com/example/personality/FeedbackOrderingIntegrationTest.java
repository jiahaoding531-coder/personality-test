package com.example.personality;

import com.example.personality.ai.TextFeedbackResult;
import com.example.personality.ai.TravelFeedbackKind;
import com.example.personality.entity.RecommendationFeedback.Reaction;
import com.example.personality.service.SaveFeedbackEventCommand;
import com.example.personality.service.TravelFeedbackPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 每个 HTTP 操作独立提交；模拟响应丢失不回滚已完成的服务端事务。 */
class FeedbackOrderingIntegrationTest extends RecommendationReactionBehaviorIntegrationTest {
    @Autowired TravelFeedbackPersistence persistence;
    @Autowired com.example.personality.service.TravelFeedbackEventService languageFeedback;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.example.personality.ai.TextFeedbackGenerator generator;

    com.example.personality.service.SavedFeedbackEvent 发送自然语言() {
        return languageFeedback.create(fixture.sessionId(),fixture.recommendationId(),"这里不喜欢",
                new com.example.personality.dto.TravelQuerySnapshot(List.of(),List.of(),240,10000,null,java.util.Map.of()),
                30.242,120.14,com.example.personality.ai.AiCredentials.forUser(com.example.personality.ai.AiProvider.DEEPSEEK,"test-key"));
    }
    TextFeedbackResult 解析为不喜欢() {
        return new TextFeedbackResult(TravelFeedbackKind.PLACE_OPINION,Reaction.DISLIKE,Set.of(),List.of(),List.of(),"已记录",false,false);
    }

    void 自然语言改为不喜欢() {
        persistence.saveEventAndOptionalReaction(new SaveFeedbackEventCommand(fixture.sessionId(),
                fixture.recommendationId(), "这个地方不喜欢", new TextFeedbackResult(
                TravelFeedbackKind.PLACE_OPINION, Reaction.DISLIKE, Set.of(), List.of(), List.of(), "已记录", false, false)));
    }

    @Test void 按钮已提交但响应丢失随后自然语言成功旧事件重试返回当前状态() throws Exception {
        UUID old = UUID.randomUUID(); String time = Instant.now().toString();
        mockMvc.perform(feedback(old,time,"LIKE").header("X-Feedback-Version","0"))
                .andExpect(status().isOk()).andExpect(jsonPath("feedbackState.version").value(1));
        自然语言改为不喜欢();
        mockMvc.perform(feedback(old,time,"LIKE").header("X-Feedback-Version","0"))
                .andExpect(status().isOk()).andExpect(jsonPath("reaction").value("DISLIKE"))
                .andExpect(jsonPath("feedbackState.version").value(2));
        assertEquals("DISLIKE", jdbc.queryForObject("SELECT reaction FROM recommendation_feedback WHERE recommendation_id=?",String.class,fixture.recommendationId()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM behavior_events WHERE session_id=?",Integer.class,fixture.sessionId()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM recommendation_feedback_events WHERE session_id=?",Integer.class,fixture.sessionId()));
    }

    @Test void 从未到达服务端的旧操作不得在新反馈之后补写() throws Exception {
        自然语言改为不喜欢();
        mockMvc.perform(feedback(UUID.randomUUID(),Instant.now().toString(),"LIKE").header("X-Feedback-Version","0"))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("STALE_FEEDBACK_VERSION"));
        assertEquals("DISLIKE",jdbc.queryForObject("SELECT reaction FROM recommendation_feedback WHERE recommendation_id=?",String.class,fixture.recommendationId()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM behavior_events WHERE session_id=?",Integer.class,fixture.sessionId()));
    }

    @Test void 旧撤销重试返回新自然语言状态且不能再次删除() throws Exception {
        mockMvc.perform(feedback(UUID.randomUUID(),Instant.now().toString(),"LIKE"));
        UUID clear = UUID.randomUUID(); String time = Instant.now().toString();
        mockMvc.perform(feedback(clear,time,null).header("X-Feedback-State","1").header("X-Feedback-Version","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("reaction").doesNotExist())
                .andExpect(jsonPath("version").value(2));
        自然语言改为不喜欢();
        mockMvc.perform(feedback(clear,time,null).header("X-Feedback-State","1").header("X-Feedback-Version","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("reaction").value("DISLIKE"))
                .andExpect(jsonPath("version").value(3));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM behavior_events WHERE session_id=?",Integer.class,fixture.sessionId()));
    }

    @Test void 同事件修改期望版本必须冲突且反馈版本不增长() throws Exception {
        UUID id=UUID.randomUUID(); String time=Instant.now().toString();
        mockMvc.perform(feedback(id,time,"LIKE").header("X-Feedback-Version","0")).andExpect(status().isOk());
        mockMvc.perform(feedback(id,time,"LIKE").header("X-Feedback-Version","1"))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("BEHAVIOR_EVENT_CONFLICT"));
        assertEquals(1L,jdbc.queryForObject("SELECT feedback_revision FROM recommendations WHERE id=?",Long.class,fixture.recommendationId()));
    }

    @Test void 迟到自然语言反应不得覆盖新的按钮反馈或留下半条事件() throws Exception {
        mockMvc.perform(feedback(UUID.randomUUID(),Instant.now().toString(),"LIKE").header("X-Feedback-Version","0"))
                .andExpect(status().isOk());
        var stale=new SaveFeedbackEventCommand(fixture.sessionId(),fixture.recommendationId(),"这里不喜欢",
                new TextFeedbackResult(TravelFeedbackKind.PLACE_OPINION,Reaction.DISLIKE,Set.of(),List.of(),List.of(),"已记录",false,false),0L);
        var error=assertThrows(com.example.personality.exception.BehaviorRequestException.class,
                ()->persistence.saveEventAndOptionalReaction(stale));
        assertEquals("STALE_FEEDBACK_VERSION",error.code());
        assertEquals("LIKE",jdbc.queryForObject("SELECT reaction FROM recommendation_feedback WHERE recommendation_id=?",String.class,fixture.recommendationId()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM recommendation_feedback_events WHERE session_id=?",Integer.class,fixture.sessionId()));
    }

    @Test @org.junit.jupiter.api.Timeout(25)
    void 模型解析在途时新按钮成功后旧解析写入被服务端拒绝() throws Exception {
        var started=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        org.mockito.Mockito.when(generator.interpret(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call->{ started.countDown(); assertTrue(release.await(8,java.util.concurrent.TimeUnit.SECONDS)); return 解析为不喜欢(); });
        try {
            var old=pool.submit(this::发送自然语言);
            assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
            mockMvc.perform(feedback(UUID.randomUUID(),Instant.now().toString(),"LIKE").header("X-Feedback-Version","0"))
                    .andExpect(status().isOk());
            release.countDown();
            var error=assertThrows(java.util.concurrent.ExecutionException.class,()->old.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("STALE_FEEDBACK_VERSION",((com.example.personality.exception.BehaviorRequestException)error.getCause()).code());
            assertEquals("LIKE",jdbc.queryForObject("SELECT reaction FROM recommendation_feedback WHERE recommendation_id=?",String.class,fixture.recommendationId()));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM recommendation_feedback_events WHERE session_id=?",Integer.class,fixture.sessionId()));
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)); }
    }

    @Test void 未确认按钮后新的自然语言由服务端读版本旧按钮再到仍幂等() throws Exception {
        UUID id=UUID.randomUUID(); String time=Instant.now().toString();
        mockMvc.perform(feedback(id,time,"LIKE").header("X-Feedback-Version","0")).andExpect(status().isOk());
        org.mockito.Mockito.when(generator.interpret(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                .thenReturn(解析为不喜欢());
        var saved=发送自然语言();
        assertEquals("DISLIKE",saved.feedbackState().reaction()); assertEquals(2,saved.feedbackState().version());
        mockMvc.perform(feedback(id,time,"LIKE").header("X-Feedback-Version","0"))
                .andExpect(status().isOk()).andExpect(jsonPath("feedbackState.version").value(2))
                .andExpect(jsonPath("reaction").value("DISLIKE"));
    }
}
