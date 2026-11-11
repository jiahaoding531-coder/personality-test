package com.example.personality;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Transactional(propagation=Propagation.NOT_SUPPORTED)
class RecommendationReactionConcurrencyTest extends RecommendationReactionBehaviorIntegrationTest {
    @Test @org.junit.jupiter.api.Timeout(25)
    void 两个同版本新操作只允许一个成功并保持事件与投影一致() throws Exception {
        var pool=Executors.newFixedThreadPool(2); var latch=new CountDownLatch(1); String time=Instant.now().toString();
        try {
            List<Future<Integer>> jobs=new ArrayList<>();
            for(String reaction:List.of("LIKE","DISLIKE")) jobs.add(pool.submit(()->{
                assertTrue(latch.await(5,TimeUnit.SECONDS));
                return mockMvc.perform(feedback(UUID.randomUUID(),time,reaction).header("X-Feedback-Version","0")).andReturn().getResponse().getStatus();
            }));
            latch.countDown();
            var statuses=new ArrayList<Integer>(); for(var job:jobs) statuses.add(job.get(10,TimeUnit.SECONDS));
            Collections.sort(statuses); assertEquals(List.of(200,409),statuses);
            assertEquals(1L,jdbc.queryForObject("SELECT feedback_revision FROM recommendations WHERE id=?",Long.class,fixture.recommendationId()));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM behavior_events WHERE session_id=?",Integer.class,fixture.sessionId()));
            assertEquals(jdbc.queryForObject("SELECT reaction FROM behavior_events WHERE session_id=?",String.class,fixture.sessionId()),
                    jdbc.queryForObject("SELECT reaction FROM recommendation_feedback WHERE recommendation_id=?",String.class,fixture.recommendationId()));
        } finally { latch.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS)); }
    }
    @Test void 首次并发反馈不撞唯一约束且各命令留有事件() throws Exception {
        var pool=Executors.newFixedThreadPool(2); var latch=new CountDownLatch(1); String now=Instant.now().toString();
        try {
            List<Future<Integer>> jobs=new ArrayList<>();
            for(String reaction:List.of("LIKE","DISLIKE")) jobs.add(pool.submit(()->{
                latch.await(); return mockMvc.perform(feedback(UUID.randomUUID(),now,reaction)).andReturn().getResponse().getStatus();
            }));
            latch.countDown();
            for(var job:jobs) assertEquals(200,job.get(15,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM recommendation_feedback WHERE recommendation_id=?",Integer.class,fixture.recommendationId()));
            assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM behavior_events WHERE session_id=?",Integer.class,fixture.sessionId()));
        } finally { pool.shutdownNow(); }
    }
}
