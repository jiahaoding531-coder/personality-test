package com.example.personality;

import org.junit.jupiter.api.*;
import org.springframework.transaction.annotation.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/** 删除事件/反馈共同事务，或重复事件重放业务，都会破坏这些数据库断言。 */
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class RecommendationReactionBehaviorIntegrationTest extends BehaviorEventHttpTestBase {
    @BeforeEach void 准备可计算旅行画像() {
        jdbc.update("INSERT INTO travel_profiles(session_id,nature,culture,food,photography,hidden_gems,crowd_tolerance,walking,planning) VALUES(?,50,50,50,50,50,50,50,50)",fixture.sessionId());
    }
    @AfterEach void 清理夹具() { jdbc.update("DELETE FROM test_sessions WHERE id=?",fixture.sessionId()); }
    MockHttpServletRequestBuilder feedback(UUID event,String time,String reaction) {
        String path="/api/travel/sessions/"+fixture.sessionId()+"/recommendations/"+fixture.recommendationId()+"/feedback";
        var request=reaction==null?delete(path):post(path).contentType("application/json").content("{\"reaction\":\""+reaction+"\"}");
        request.with(csrf()).header("X-Session-Token",fixture.token().toString());
        if(event!=null) request.header("X-Behavior-Event-Id",event.toString());
        if(time!=null) request.header("X-Behavior-Occurred-At",time);
        return request;
    }
    @Test void 反馈和事件同时持久化且旧重试不会重放反应() throws Exception {
        UUID set=UUID.randomUUID(),clear=UUID.randomUUID(); String time=Instant.now().toString();
        mockMvc.perform(feedback(set,time,"LIKE")).andExpect(status().isOk());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM behavior_events WHERE event_id=? AND action='REACTION_SET'",Integer.class,set));
        mockMvc.perform(feedback(clear,time,null)).andExpect(status().isNoContent());
        mockMvc.perform(feedback(set,time,"LIKE")).andExpect(status().isOk());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM recommendation_feedback WHERE recommendation_id=?",Integer.class,fixture.recommendationId()));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM behavior_events WHERE session_id=?",Integer.class,fixture.sessionId()));
        mockMvc.perform(feedback(set,time,"DISLIKE")).andExpect(status().isConflict());
    }
    @Test void 只提供单个头或非法时间不能保存业务() throws Exception {
        mockMvc.perform(feedback(UUID.randomUUID(),null,"LIKE")).andExpect(status().isBadRequest());
        mockMvc.perform(feedback(null,Instant.now().toString(),"LIKE")).andExpect(status().isBadRequest());
        mockMvc.perform(feedback(UUID.randomUUID(),"not-time","LIKE")).andExpect(status().isBadRequest());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM recommendation_feedback WHERE recommendation_id=?",Integer.class,fixture.recommendationId()));
    }
    @Test void 老客户端仍工作但也保存事实() throws Exception {
        mockMvc.perform(feedback(null,null,"DISLIKE")).andExpect(status().isOk());
        mockMvc.perform(feedback(null,null,null)).andExpect(status().isNoContent());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM behavior_events WHERE session_id=?",Integer.class,fixture.sessionId()));
    }
    @Test void 事件冲突不能半写反馈() throws Exception {
        var nav=navigation(); send(nav).andExpect(status().isCreated());
        mockMvc.perform(feedback(UUID.fromString(nav.path("eventId").asString()),nav.path("occurredAt").asString(),"LIKE"))
                .andExpect(status().isConflict());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM recommendation_feedback WHERE recommendation_id=?",Integer.class,fixture.recommendationId()));
    }
}
