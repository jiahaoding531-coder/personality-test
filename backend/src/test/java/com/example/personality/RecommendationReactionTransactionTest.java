package com.example.personality;
import com.example.personality.service.TravelFeedbackPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.*;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 注入真实业务写入后的故障；不能靠测试类外层回滚伪装原子性。 */
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class RecommendationReactionTransactionTest extends RecommendationReactionBehaviorIntegrationTest {
    @MockitoSpyBean TravelFeedbackPersistence persistence;
    @Test void 反馈写后失败必须连同行为事件整体回滚() throws Exception {
        doAnswer(invocation->{ invocation.callRealMethod(); throw new IllegalStateException("测试注入写入失败"); })
                .when(persistence).upsertReaction(eq(fixture.recommendationId()),any());
        mockMvc.perform(feedback(UUID.randomUUID(),Instant.now().toString(),"LIKE")).andExpect(status().isInternalServerError());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM recommendation_feedback WHERE recommendation_id=?",Integer.class,fixture.recommendationId()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM behavior_events WHERE session_id=?",Integer.class,fixture.sessionId()));
        assertEquals(0L,jdbc.queryForObject("SELECT feedback_revision FROM recommendations WHERE id=?",Long.class,fixture.recommendationId()));
    }
}
