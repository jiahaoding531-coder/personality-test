package com.example.personality;

import com.example.personality.dto.*;
import com.example.personality.service.AMapRestaurantSearchService;
import org.junit.jupiter.api.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/** 删除编排层的稳定 ID、条件绑定或短事务存储，应使这些测试失败。只模拟外部收费服务。 */
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class RestaurantSearchProvenanceIntegrationTest extends BehaviorEventHttpTestBase {
    @MockitoBean AMapRestaurantSearchService upstream;
    @BeforeEach void 外部返回公开测试目标() {
        when(upstream.search(any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"高德调用不能占用数据库事务");
            RestaurantSearchRequest r=invocation.getArgument(0);
            return new RestaurantSearchResponse(UUID.randomUUID(),Instant.now(),"SUCCESS",r.radius()==null?3000:r.radius(),
                    "GCJ02","STRAIGHT_LINE",List.of(new Restaurant("PUBLIC_POI","公开测试餐饮","餐饮服务","050000",
                    "公开测试地址",33.735,113.3077,100,null,null,null,null,null,null)),r.page()==null?1:r.page());
        });
    }
    @AfterEach void 清理本测试会话() { jdbc.update("DELETE FROM test_sessions WHERE id=?",fixture.sessionId()); }
    String body(UUID search,int page) {
        return "{\"latitude\":33.735,\"longitude\":113.3077,\"coordinateSystem\":\"WGS84\",\"page\":"+page
                +(search==null?"":",\"searchSessionId\":\""+search+"\"")+"}";
    }
    JsonNode search(UUID id,int page) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/api/travel/sessions/{id}/restaurants/search",fixture.sessionId())
                .with(csrf()).header("X-Session-Token",fixture.token().toString()).contentType("application/json").content(body(id,page)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    @Test void 分页共用搜索ID每页独立来源且最小快照准确() throws Exception {
        UUID id=UUID.randomUUID(); var first=search(id,1); var second=search(id,2);
        assertEquals(id.toString(),first.path("searchSessionId").asString());
        assertEquals(id.toString(),second.path("searchSessionId").asString());
        assertTrue(first.hasNonNull("resultSetId"));
        assertNotEquals(first.path("resultSetId"),second.path("resultSetId"));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM restaurant_search_batches WHERE session_id=?",Integer.class,fixture.sessionId()));
        assertEquals("公开测试餐饮",jdbc.queryForObject("SELECT name FROM restaurant_search_items WHERE result_set_id=?",String.class,
                UUID.fromString(first.path("resultSetId").asString())));
        var event=navigation().put("targetType","RESTAURANT").put("provider","AMAP").put("targetId","PUBLIC_POI")
                .putNull("recommendationId").put("searchSessionId",id.toString()).put("resultSetId",first.path("resultSetId").asString());
        send(event).andExpect(status().isCreated());
        send(event.deepCopy().put("eventId",UUID.randomUUID().toString()).put("targetId","FORGED")).andExpect(status().isNotFound());
    }
    @Test void 同ID改变条件被拒绝新ID可搜索() throws Exception {
        UUID id=UUID.randomUUID(); search(id,1);
        mockMvc.perform(post("/api/travel/sessions/{id}/restaurants/search",fixture.sessionId()).with(csrf())
                .header("X-Session-Token",fixture.token().toString()).contentType("application/json")
                .content(body(id,2).replace("33.735","33.736"))).andExpect(status().isConflict());
        mockMvc.perform(post("/api/travel/sessions/{id}/restaurants/search",fixture.sessionId()).with(csrf())
                .header("X-Session-Token",fixture.token().toString()).contentType("application/json")
                .content(body(id,2).replace("33.735","33.73500"))).andExpect(status().isOk());
        search(UUID.randomUUID(),1);
    }
    @Test void 新ID不能从第二页开始而旧客户端仍可独立请求() throws Exception {
        mockMvc.perform(post("/api/travel/sessions/{id}/restaurants/search",fixture.sessionId()).with(csrf())
                .header("X-Session-Token",fixture.token().toString()).contentType("application/json")
                .content(body(UUID.randomUUID(),2))).andExpect(status().isGone());
        assertTrue(search(null,2).hasNonNull("resultSetId"));
    }
    @Test void 其他会话不能沿用搜索ID() throws Exception {
        UUID id=UUID.randomUUID(); search(id,1);
        var other=BehaviorTestFixtures.create(jdbc,null);
        try {
            mockMvc.perform(post("/api/travel/sessions/{id}/restaurants/search",other.sessionId()).with(csrf())
                    .header("X-Session-Token",other.token().toString()).contentType("application/json").content(body(id,2)))
                    .andExpect(status().isNotFound());
        } finally { jdbc.update("DELETE FROM test_sessions WHERE id=?",other.sessionId()); }
    }
    @Test void 空结果也有可验证来源且不写本地点() throws Exception {
        int count=jdbc.queryForObject("SELECT count(*) FROM places",Integer.class);
        doReturn(new RestaurantSearchResponse(UUID.randomUUID(),Instant.now(),"NO_RESULTS",3000,"GCJ02","STRAIGHT_LINE",List.of())).when(upstream).search(any());
        var result=search(UUID.randomUUID(),1);
        assertTrue(result.hasNonNull("resultSetId"));
        assertEquals("NO_RESULTS",jdbc.queryForObject("SELECT status FROM restaurant_search_batches WHERE result_set_id=?",String.class,UUID.fromString(result.path("resultSetId").asString())));
        assertEquals(count,jdbc.queryForObject("SELECT count(*) FROM places",Integer.class));
    }
}
