package com.example.personality;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 删除幂等分支、忽略来源校验或只做先查再插，均应被这些真实边界测试发现。 */
abstract class BehaviorEventHttpTestBase extends IntegrationTestBase {
    @Autowired JdbcTemplate jdbc;
    BehaviorTestFixtures.Fixture fixture;

    @BeforeEach void 准备本测试来源() { fixture = BehaviorTestFixtures.create(jdbc, null); }

    ObjectNode navigation() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("eventId", UUID.randomUUID().toString());
        body.put("schemaVersion", 1);
        body.put("targetType", "PLACE"); body.put("provider", "LOCAL");
        body.put("targetId", Long.toString(fixture.placeId()));
        body.put("action", "NAVIGATION_REQUESTED");
        body.put("occurredAt", Instant.now().toString());
        body.put("recommendationId", fixture.recommendationId());
        return body;
    }
    MockHttpServletRequestBuilder request(ObjectNode body) {
        return post("/api/travel/sessions/{id}/behavior-events", fixture.sessionId())
                .with(csrf()).header("X-Session-Token", fixture.token().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body.toString());
    }
    ResultActions send(ObjectNode body) throws Exception { return mockMvc.perform(request(body)); }

}

class BehaviorEventIntegrationTest extends BehaviorEventHttpTestBase {
    @Test void 相同事件重复只保存一条() throws Exception {
        ObjectNode body = navigation();
        send(body).andExpect(status().isCreated()).andExpect(jsonPath("duplicate").value(false));
        send(body).andExpect(status().isOk()).andExpect(jsonPath("duplicate").value(true));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM behavior_events WHERE event_id=?",
                Integer.class, UUID.fromString(body.path("eventId").asString())));
        assertNull(jdbc.queryForObject("SELECT user_id FROM behavior_events WHERE event_id=?", Long.class,
                UUID.fromString(body.path("eventId").asString())));
    }

    @Test void 时间的等价格式和可空字段规范化后仍幂等() throws Exception {
        ObjectNode body = navigation();
        body.put("occurredAt", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
        send(body).andExpect(status().isCreated());
        body.put("occurredAt", body.path("occurredAt").asString().replace("Z", ".000+00:00"));
        body.putNull("searchSessionId"); body.putArray("visibleSources");
        send(body).andExpect(status().isOk());
    }

    @Test void 相同ID修改目标或动作或时间返回冲突() throws Exception {
        ObjectNode body = navigation(); send(body).andExpect(status().isCreated());
        ObjectNode changed = body.deepCopy().put("targetId", "99999999");
        send(changed).andExpect(status().isConflict());
        changed = body.deepCopy().put("action", "NEXT_BATCH_REQUESTED").putNull("targetId").putNull("recommendationId");
        send(changed).andExpect(status().isConflict());
        changed = body.deepCopy().put("occurredAt", Instant.now().plusSeconds(1).toString());
        send(changed).andExpect(status().isConflict());
    }

    @Test void 伪造来源和会话被拒绝() throws Exception {
        ObjectNode body = navigation().put("recommendationId", Long.MAX_VALUE);
        send(body).andExpect(status().isNotFound());
        BehaviorTestFixtures.Fixture other = BehaviorTestFixtures.create(jdbc, null);
        body = navigation().put("recommendationId", other.recommendationId()).put("targetId", Long.toString(other.placeId()));
        send(body).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/travel/sessions/{id}/behavior-events", fixture.sessionId())
                .with(csrf()).header("X-Session-Token", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(navigation().toString()))
                .andExpect(status().isNotFound());
    }

    @Test void 未授权坏请求仍优先返回404() throws Exception {
        mockMvc.perform(post("/api/travel/sessions/{id}/behavior-events", fixture.sessionId())
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isNotFound());
    }

    @Test void 登录其他用户不能凭旧令牌写入已归属会话() throws Exception {
        var owner = registerAndLogin(uniqueUsername("behavior_owner"), "test_password_123");
        var created = mockMvc.perform(post("/api/travel/sessions").session(owner).with(csrf()))
                .andExpect(status().isCreated()).andReturn();
        var session = objectMapper.readTree(created.getResponse().getContentAsString());
        var stranger = registerAndLogin(uniqueUsername("behavior_stranger"), "test_password_123");
        mockMvc.perform(post("/api/travel/sessions/{id}/behavior-events", session.path("sessionId").asLong())
                .session(stranger).with(csrf()).header("X-Session-Token", session.path("accessToken").asString())
                .contentType(MediaType.APPLICATION_JSON).content(navigation().toString()))
                .andExpect(status().isNotFound());
    }

    @Test void 伪造餐饮搜索结果被拒绝() throws Exception {
        ObjectNode body = navigation().put("targetType", "RESTAURANT").put("provider", "AMAP")
                .put("targetId", "FAKE_POI").putNull("recommendationId")
                .put("searchSessionId", UUID.randomUUID().toString()).put("resultSetId", UUID.randomUUID().toString());
        send(body).andExpect(status().isNotFound());
    }

    @Test void 真实餐饮来源可记录且删除快照后旧事件仍可重试() throws Exception {
        UUID search = UUID.randomUUID(), result = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO restaurant_search_batches(result_set_id,search_session_id,session_id,page,radius,status,expires_at)
                VALUES (?,?,?,1,3000,'SUCCESS',CURRENT_TIMESTAMP + INTERVAL '7 days')
                """, result, search, fixture.sessionId());
        jdbc.update("""
                INSERT INTO restaurant_search_items(result_set_id,poi_id,name,latitude,longitude,distance_meters,rank_no)
                VALUES (?, 'PUBLIC_TEST_POI','公开测试餐饮',33.73,113.3,100,1)
                """, result);
        ObjectNode body = navigation().put("targetType", "RESTAURANT").put("provider", "AMAP")
                .put("targetId", "PUBLIC_TEST_POI").putNull("recommendationId")
                .put("searchSessionId", search.toString()).put("resultSetId", result.toString());
        send(body).andExpect(status().isCreated());
        jdbc.update("DELETE FROM restaurant_search_batches WHERE result_set_id=?", result);
        send(body).andExpect(status().isOk()).andExpect(jsonPath("duplicate").value(true));
        send(body.deepCopy().put("eventId", UUID.randomUUID().toString())).andExpect(status().isNotFound());
        assertEquals(result.toString(), jdbc.queryForObject("SELECT result_set_id::text FROM behavior_events WHERE event_id=?",
                String.class, UUID.fromString(body.path("eventId").asString())));
    }

    @Test void 已过期来源不能产生新事件() throws Exception {
        UUID search = UUID.randomUUID(), result = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO restaurant_search_batches(result_set_id,search_session_id,session_id,page,radius,status,expires_at)
                VALUES (?,?,?,1,3000,'NO_RESULTS',CURRENT_TIMESTAMP - INTERVAL '1 second')
                """, result, search, fixture.sessionId());
        ObjectNode body = navigation().put("targetType", "RESTAURANT").put("provider", "AMAP")
                .put("action", "NEXT_BATCH_REQUESTED").putNull("targetId").putNull("recommendationId")
                .put("searchSessionId", search.toString()).put("resultSetId", result.toString());
        send(body).andExpect(status().isNotFound());
    }

    @Test void 列表级换批不冒充对所有地点的不喜欢() throws Exception {
        ObjectNode body = navigation().put("action", "NEXT_BATCH_REQUESTED").putNull("targetId")
                .putNull("recommendationId").put("recommendationBatchNo", 1);
        send(body).andExpect(status().isCreated());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM recommendation_feedback WHERE recommendation_id=?",
                Integer.class, fixture.recommendationId()));
        send(body.deepCopy().put("eventId", UUID.randomUUID().toString()).put("recommendationBatchNo", 999))
                .andExpect(status().isNotFound());
    }
}
