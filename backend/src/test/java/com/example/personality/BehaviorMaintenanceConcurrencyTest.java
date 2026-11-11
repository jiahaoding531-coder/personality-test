package com.example.personality;

import com.example.personality.domain.*;
import com.example.personality.service.BehaviorEventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 用真实 PostgreSQL 行锁检查维护和事件写入的锁顺序，不模拟数据库事务。 */
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class BehaviorMaintenanceConcurrencyTest extends BehaviorEventHttpTestBase {
    @Autowired BehaviorEventService events;
    @Autowired PlatformTransactionManager manager;

    @Test void 内部维护持有用户锁时事件不能先占用来源锁() throws Exception {
        Long user=jdbc.queryForObject("INSERT INTO users(username,password_hash) VALUES(?,?) RETURNING id",Long.class,
                "lock_"+UUID.randomUUID(),"测试不可登录占位");
        var f=BehaviorTestFixtures.create(jdbc,user);
        UUID search=UUID.randomUUID(), result=UUID.randomUUID();
        jdbc.update("INSERT INTO restaurant_search_batches(result_set_id,search_session_id,session_id,user_id,page,radius,status,expires_at) VALUES(?,?,?,?,1,3000,'SUCCESS',?)",
                result,search,f.sessionId(),user,java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO restaurant_search_items(result_set_id,poi_id,name,latitude,longitude,rank_no) VALUES(?,'PUBLIC_POI','公开测试餐饮',33.7,113.3,1)",result);
        var command=new BehaviorEventCommand(UUID.randomUUID(),1,BehaviorTargetType.RESTAURANT,BehaviorProvider.AMAP,
                "PUBLIC_POI",BehaviorAction.NAVIGATION_REQUESTED,Instant.now(),null,null,search,result,null,List.of());
        var workers=Executors.newSingleThreadExecutor();
        var pending=new java.util.concurrent.atomic.AtomicReference<Future<?>>();
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx->{
                jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,user);
                pending.set(workers.submit(()->events.record(new AccessScope(f.sessionId(),user),command)));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                boolean blocked=false;
                while(System.nanoTime()<deadline) {
                    jdbc.execute("SELECT pg_stat_clear_snapshot()");
                    blocked=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND (query LIKE '%users%' OR query LIKE '%INSERT INTO behavior_events%'))",Boolean.class));
                    if(blocked) break;
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                }
                assertTrue(blocked,"事件应等待维护持有的用户锁");
                // 如果事件先锁来源再等用户，这里立即失败，也暴露潜在死锁。
                assertDoesNotThrow(()->jdbc.queryForObject("SELECT result_set_id FROM restaurant_search_batches WHERE result_set_id=? FOR UPDATE NOWAIT",UUID.class,result));
            });
            pending.get().get(10,TimeUnit.SECONDS);
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM behavior_events WHERE event_id=?",Integer.class,command.eventId()));
        } finally {
            workers.shutdown(); workers.awaitTermination(10,TimeUnit.SECONDS);
            jdbc.update("DELETE FROM test_sessions WHERE id=?",f.sessionId());
            jdbc.update("DELETE FROM users WHERE id=?",user);
            jdbc.update("DELETE FROM test_sessions WHERE id=?",fixture.sessionId());
        }
    }
}
