package com.example.personality;

import com.example.personality.ai.TextFeedbackResult;
import com.example.personality.ai.TravelFeedbackKind;
import com.example.personality.entity.RecommendationFeedback;
import com.example.personality.entity.RecommendationFeedbackEvent;
import com.example.personality.repository.RecommendationFeedbackEventRepository;
import com.example.personality.service.SaveFeedbackEventCommand;
import com.example.personality.service.TravelFeedbackPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/** 真 PostgreSQL + 真 Repository；只在真实插入后暂停线程，控制锁交错，不模拟数据库结果。 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TravelFeedbackLockOrderIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired TravelFeedbackPersistence persistence;
    @MockitoSpyBean RecommendationFeedbackEventRepository repository;

    @Test
    @Timeout(35)
    void 同推荐自然语言反应并发提交不得因外键共享锁升级而死锁() throws Exception {
        var fixture = BehaviorTestFixtures.create(jdbc, null);
        var firstSaved = new CountDownLatch(1);
        var secondSaved = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var saves = new AtomicInteger();
        String secondConnection = "feedback_lock_" + UUID.randomUUID();
        var workers = Executors.newFixedThreadPool(2);
        try {
            // Spring Data 接口的 spy 委托给原始代理，不能对抽象接口调用 callRealMethod。
            var realRepository = mockingDetails(repository).getMockCreationSettings().getDefaultAnswer();
            doAnswer(call -> {
                RecommendationFeedbackEvent event = (RecommendationFeedbackEvent) realRepository.answer(call);
                if (event.getSessionId().equals(fixture.sessionId())) {
                    if (saves.incrementAndGet() == 1) firstSaved.countDown();
                    else secondSaved.countDown();
                    assertTrue(release.await(12, TimeUnit.SECONDS), "测试同步必须有界，不能无限等待");
                }
                return event;
            }).when(repository).save(any(RecommendationFeedbackEvent.class));

            Future<?> first = workers.submit(() -> write(fixture, RecommendationFeedback.Reaction.LIKE, "first_feedback"));
            if (!firstSaved.await(10, TimeUnit.SECONDS)) {
                first.get(1, TimeUnit.SECONDS);
                fail("第一条反馈应完成真实事件插入");
            }
            Future<?> second = workers.submit(() -> write(fixture, RecommendationFeedback.Reaction.DISLIKE, secondConnection));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            boolean observed = false;
            while (System.nanoTime() < deadline) {
                boolean blocked = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM pg_stat_activity
                          WHERE datname=current_database() AND application_name=? AND wait_event_type='Lock')
                        """, Boolean.class, secondConnection));
                // 旧代码两边均完成插入后再抢排他锁；正确代码第二边先等第一边推荐锁。
                if (secondSaved.getCount() == 0 || blocked) { observed = true; break; }
                TimeUnit.MILLISECONDS.sleep(10);
            }
            assertTrue(observed, "必须观察到第二个事务已进入锁竞争，不能只靠随机调度");
            release.countDown();
            assertDoesNotThrow(() -> first.get(10, TimeUnit.SECONDS), "第一条自然语言反馈不能死锁或半写");
            assertDoesNotThrow(() -> second.get(10, TimeUnit.SECONDS), "第二条自然语言反馈不能死锁或半写");
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM recommendation_feedback_events WHERE session_id=?", Integer.class, fixture.sessionId()));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM recommendation_feedback WHERE recommendation_id=?", Integer.class, fixture.recommendationId()));
            assertEquals("DISLIKE", jdbc.queryForObject("SELECT reaction FROM recommendation_feedback WHERE recommendation_id=?", String.class, fixture.recommendationId()));
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(12, TimeUnit.SECONDS), "后台事务应在数据库超时内终止");
            jdbc.update("DELETE FROM test_sessions WHERE id=?", fixture.sessionId());
        }
    }

    private void write(BehaviorTestFixtures.Fixture fixture, RecommendationFeedback.Reaction reaction, String connectionName) {
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            jdbc.execute("SET LOCAL statement_timeout='8s'");
            jdbc.execute("SET LOCAL lock_timeout='6s'");
            jdbc.queryForObject("SELECT set_config('application_name',?,true)", String.class, connectionName);
            var result = new TextFeedbackResult(TravelFeedbackKind.PLACE_OPINION, reaction,
                    Set.of(), List.of(), List.of(), "并发反馈测试", false, false);
            persistence.saveEventAndOptionalReaction(new SaveFeedbackEventCommand(
                    fixture.sessionId(), fixture.recommendationId(), "明确反应测试", result));
        });
    }
}
