package com.example.personality;

import com.example.personality.domain.*;
import com.example.personality.service.BehaviorEventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 回归对账：明确行为流水不能被当作景点反馈或画像输入。 */
class BehaviorRecommendationIsolationTest extends IntegrationTestBase {
    @Autowired BehaviorEventService events;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean Clock clock;
    @Test void 导航换批餐饮排除不改变相同条件的景点分数及问卷画像() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-09T04:00:00Z"));
        var ref=createTravelSession(null); answerAllTravelQuestions(ref,3,null);
        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/submit",ref.id()).with(csrf()),ref)).andExpect(status().isOk());
        java.util.function.Supplier<JsonNode> recommend=()->{
            try { return objectMapper.readTree(mockMvc.perform(withToken(post("/api/travel/sessions/{id}/recommendations",ref.id()).with(csrf())
                .contentType("application/json").content("{\"latitude\":30.242,\"longitude\":120.14,\"excludeSeen\":false}"),ref))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)); }
            catch(Exception error) { throw new AssertionError(error); }
        };
        var before=recommend.get(); assertFalse(before.path("places").isEmpty());
        String profile=jdbc.queryForObject("SELECT row_to_json(p)::text FROM travel_profiles p WHERE session_id=?",String.class,ref.id());
        var first=before.path("places").get(0); var scope=new AccessScope(ref.id(),null);
        events.record(scope,new BehaviorEventCommand(UUID.randomUUID(),1,BehaviorTargetType.PLACE,BehaviorProvider.LOCAL,
            first.path("placeId").asString(),BehaviorAction.NAVIGATION_REQUESTED,clock.instant(),first.path("recommendationId").asLong(),null,null,null,null,List.of()));
        events.record(scope,new BehaviorEventCommand(UUID.randomUUID(),1,BehaviorTargetType.PLACE,BehaviorProvider.LOCAL,
            null,BehaviorAction.NEXT_BATCH_REQUESTED,clock.instant(),null,before.path("batchNo").asInt(),null,null,null,List.of()));
        UUID search=UUID.randomUUID(), result=UUID.randomUUID();
        jdbc.update("INSERT INTO restaurant_search_batches(result_set_id,search_session_id,session_id,page,radius,status,expires_at) VALUES(?,?,?,1,3000,'SUCCESS',?)",
            result,search,ref.id(),java.sql.Timestamp.from(clock.instant().plusSeconds(3600)));
        jdbc.update("INSERT INTO restaurant_search_items(result_set_id,poi_id,name,latitude,longitude,rank_no) VALUES(?,'PUBLIC_POI','公开测试餐饮',33.7,113.3,1)",result);
        events.record(scope,new BehaviorEventCommand(UUID.randomUUID(),1,BehaviorTargetType.RESTAURANT,BehaviorProvider.AMAP,
            "PUBLIC_POI",BehaviorAction.EXCLUDE,clock.instant(),null,null,search,result,null,List.of()));
        var after=recommend.get(); assertEquals(before.path("places").size(),after.path("places").size());
        for(int i=0;i<before.path("places").size();i++) {
            assertEquals(before.path("places").get(i).path("placeId"),after.path("places").get(i).path("placeId"));
            assertEquals(before.path("places").get(i).path("scoreBreakdown"),after.path("places").get(i).path("scoreBreakdown"));
        }
        assertEquals(profile,jdbc.queryForObject("SELECT row_to_json(p)::text FROM travel_profiles p WHERE session_id=?",String.class,ref.id()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM recommendation_feedback f JOIN recommendations r ON r.id=f.recommendation_id WHERE r.session_id=?",Integer.class,ref.id()));
    }
}
