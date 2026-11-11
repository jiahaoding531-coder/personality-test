package com.example.personality;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ScenicBatchProvenanceIntegrationTest extends BehaviorEventHttpTestBase {
    ObjectNode next(Long recommendationId,Integer batch) {
        var body=navigation().put("action","NEXT_BATCH_REQUESTED").putNull("targetId").putNull("recommendationId");
        if(batch!=null) body.put("recommendationBatchNo",batch);
        body.putArray("visibleSources").addObject().put("recommendationId",recommendationId).put("targetId",Long.toString(fixture.placeId()));
        return body;
    }
    long secondBatch() {
        jdbc.update("INSERT INTO recommendation_batches(session_id,batch_no,context_time,remaining_minutes,max_distance_km) VALUES(?,2,'12:00',240,10)",fixture.sessionId());
        return jdbc.queryForObject("INSERT INTO recommendations(session_id,batch_no,rank_no,place_id,score) VALUES(?,2,1,?,0.5) RETURNING id",Long.class,fixture.sessionId(),fixture.placeId());
    }
    @Test void 相同会话真实推荐但声明错误批次必须拒绝() throws Exception {
        long second=secondBatch();
        send(next(second,1)).andExpect(status().isNotFound());
        send(next(fixture.recommendationId(),2)).andExpect(status().isNotFound());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM behavior_events WHERE session_id=?",Integer.class,fixture.sessionId()));
    }
    @Test void 非空卡片来源不能省略推荐批次() throws Exception {
        send(next(fixture.recommendationId(),null)).andExpect(status().isBadRequest());
    }
    @Test void 正常批次可记录且旧批次合法动作无需等于最新批次() throws Exception {
        long second=secondBatch();
        var body=next(second,2);
        send(body).andExpect(status().isCreated()); send(body).andExpect(status().isOk()).andExpect(jsonPath("duplicate").value(true));
        send(next(fixture.recommendationId(),1)).andExpect(status().isCreated());
    }
    @Test void 伪造与跨会话及跨用户推荐ID不能绑定本会话批次() throws Exception {
        send(next(Long.MAX_VALUE,1)).andExpect(status().isNotFound());
        var other=BehaviorTestFixtures.create(jdbc,null);
        send(next(other.recommendationId(),1)).andExpect(status().isNotFound());
        var login=registerAndLogin(uniqueUsername("batch_owner"),"test_password_123");
        var owner=jdbc.queryForObject("SELECT id FROM users WHERE username LIKE 'batch_owner_%' ORDER BY id DESC LIMIT 1",Long.class);
        var owned=BehaviorTestFixtures.create(jdbc,owner);
        send(next(owned.recommendationId(),1)).andExpect(status().isNotFound());
        mockMvc.perform(request(next(owned.recommendationId(),1)).session(login)).andExpect(status().isNotFound());
    }
    @Test void 空结果换批允许无批次但不存在的声明批次仍拒绝() throws Exception {
        var body=next(fixture.recommendationId(),null); body.putArray("visibleSources");
        send(body).andExpect(status().isCreated());
        send(next(fixture.recommendationId(),999)).andExpect(status().isNotFound());
    }
    @Test void 单卡导航声明的推荐与批次也必须对应() throws Exception {
        secondBatch();
        send(navigation().put("recommendationBatchNo",2)).andExpect(status().isNotFound());
    }
}
