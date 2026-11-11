package com.example.personality;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.*;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 对内部维护服务进行数据库效果测试；反射仅用于先证明该能力尚不存在，不开放测试专用 HTTP API。 */
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class BehaviorRetentionServiceIntegrationTest extends BehaviorEventHttpTestBase {
    @Autowired ApplicationContext context;
    final List<Long> extraSessions=new ArrayList<>();
    final List<Long> users=new ArrayList<>();
    @AfterEach void 清理测试夹具() {
        SecurityContextHolder.clearContext();
        extraSessions.add(fixture.sessionId());
        for(Long id:extraSessions) jdbc.update("DELETE FROM test_sessions WHERE id=?",id);
        for(Long id:users) jdbc.update("DELETE FROM users WHERE id=?",id);
    }
    Object invoke(String name,Class<?>[] types,Object...args) throws Throwable {
        assertTrue(context.containsBean("behaviorRetentionService"),"需要受控清理服务，不能靠直接删库");
        try { return context.getBean("behaviorRetentionService").getClass().getMethod(name,types).invoke(context.getBean("behaviorRetentionService"),args); }
        catch(InvocationTargetException failure) { throw failure.getCause(); }
    }
    void purge(Instant now,int size) throws Throwable { invoke("purgeExpired",new Class<?>[]{Instant.class,int.class},now,size); }
    UUID batch(BehaviorTestFixtures.Fixture f,Instant expiry) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO restaurant_search_batches(result_set_id,search_session_id,session_id,user_id,page,radius,status,expires_at) VALUES(?,?,?,?,1,3000,'SUCCESS',?)",
                id,UUID.randomUUID(),f.sessionId(),jdbc.queryForObject("SELECT user_id FROM test_sessions WHERE id=?",Long.class,f.sessionId()),java.sql.Timestamp.from(expiry));
        jdbc.update("INSERT INTO restaurant_search_items(result_set_id,poi_id,name,latitude,longitude,rank_no) VALUES(?,'PUBLIC_POI','公开测试餐饮',33.7,113.3,1)",id);
        return id;
    }
    @Test void 七天来源清理不删行为旧事件仍幂等新事件拒绝() throws Throwable {
        var now=Instant.now(); UUID batch=batch(fixture,now.minusSeconds(1));
        var search=jdbc.queryForObject("SELECT search_session_id FROM restaurant_search_batches WHERE result_set_id=?",UUID.class,batch);
        // 先把来源恢复有效以完成首次真实事件，再模拟到期。
        jdbc.update("UPDATE restaurant_search_batches SET expires_at=? WHERE result_set_id=?",java.sql.Timestamp.from(now.plusSeconds(60)),batch);
        var body=navigation().put("targetType","RESTAURANT").put("provider","AMAP").put("targetId","PUBLIC_POI")
                .putNull("recommendationId").put("searchSessionId",search.toString()).put("resultSetId",batch.toString());
        send(body).andExpect(status().isCreated());
        jdbc.update("UPDATE restaurant_search_batches SET expires_at=? WHERE result_set_id=?",java.sql.Timestamp.from(now),batch);
        purge(now,100);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM restaurant_search_items WHERE result_set_id=?",Integer.class,batch));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM behavior_events WHERE session_id=?",Integer.class,fixture.sessionId()));
        send(body).andExpect(status().isOk());
        send(body.deepCopy().put("eventId",UUID.randomUUID().toString())).andExpect(status().isNotFound());
    }
    @Test void 九十天边界和分块重复执行不误删未到期数据() throws Throwable {
        Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var a=navigation(); var b=navigation(); var c=navigation();
        send(a).andExpect(status().isCreated()); send(b).andExpect(status().isCreated()); send(c).andExpect(status().isCreated());
        for(var body:List.of(a,b)) jdbc.update("UPDATE behavior_events SET received_at=? WHERE event_id=?",java.sql.Timestamp.from(now.minusSeconds(90*86400L)),UUID.fromString(body.path("eventId").asString()));
        jdbc.update("UPDATE behavior_events SET received_at=? WHERE event_id=?",java.sql.Timestamp.from(now.minusSeconds(90*86400L).plusNanos(1000)),UUID.fromString(c.path("eventId").asString()));
        purge(now,1); assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM behavior_events WHERE session_id=?",Integer.class,fixture.sessionId()));
        purge(now,100); purge(now,100);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM behavior_events WHERE session_id=?",Integer.class,fixture.sessionId()));
        assertThrows(IllegalArgumentException.class,()->purge(now,0)); assertThrows(IllegalArgumentException.class,()->purge(now,1001));
    }
    @Test void 内部删除必须具备维护身份且只删指定用户新数据() throws Throwable {
        Long owner=jdbc.queryForObject("INSERT INTO users(username,password_hash) VALUES(?,?) RETURNING id",Long.class,"delete_"+UUID.randomUUID(),"测试不可登录占位");
        Long stranger=jdbc.queryForObject("INSERT INTO users(username,password_hash) VALUES(?,?) RETURNING id",Long.class,"keep_"+UUID.randomUUID(),"测试不可登录占位");
        users.add(owner); users.add(stranger);
        var own=BehaviorTestFixtures.create(jdbc,owner); var other=BehaviorTestFixtures.create(jdbc,stranger);
        extraSessions.add(own.sessionId()); extraSessions.add(other.sessionId());
        batch(own,Instant.now().plusSeconds(86400)); batch(other,Instant.now().plusSeconds(86400));
        jdbc.update("INSERT INTO recommendation_feedback(recommendation_id,reaction) VALUES(?,'LIKE')",own.recommendationId());
        int places=jdbc.queryForObject("SELECT count(*) FROM places",Integer.class);
        var events=context.getBean(com.example.personality.service.BehaviorEventService.class);
        for(var pair:List.of(new Object[]{own,owner},new Object[]{other,stranger})) {
            var f=(BehaviorTestFixtures.Fixture)pair[0];
            events.record(new com.example.personality.domain.AccessScope(f.sessionId(),(Long)pair[1]),
                new com.example.personality.domain.BehaviorEventCommand(UUID.randomUUID(),1,
                    com.example.personality.domain.BehaviorTargetType.PLACE,com.example.personality.domain.BehaviorProvider.LOCAL,
                    Long.toString(f.placeId()),com.example.personality.domain.BehaviorAction.NAVIGATION_REQUESTED,Instant.now(),
                    f.recommendationId(),null,null,null,null,List.of()));
        }
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->invoke("deleteNewBehaviorDataForUser",new Class<?>[]{Long.class},owner));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("普通用户",null,List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->invoke("deleteNewBehaviorDataForUser",new Class<?>[]{Long.class},owner));
        var identity=UsernamePasswordAuthenticationToken.authenticated("内部维护",null,List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_DATA_MAINTAINER")));
        SecurityContextHolder.getContext().setAuthentication(identity);
        invoke("deleteNewBehaviorDataForUser",new Class<?>[]{Long.class},owner);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM restaurant_search_batches WHERE user_id=?",Integer.class,owner));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM restaurant_search_batches WHERE user_id=?",Integer.class,stranger));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM behavior_events WHERE user_id=?",Integer.class,owner));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM behavior_events WHERE user_id=?",Integer.class,stranger));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM recommendation_feedback WHERE recommendation_id=?",Integer.class,own.recommendationId()));
        assertEquals(places,jdbc.queryForObject("SELECT count(*) FROM places",Integer.class));
    }
}
