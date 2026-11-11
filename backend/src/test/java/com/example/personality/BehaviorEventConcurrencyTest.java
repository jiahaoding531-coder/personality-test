package com.example.personality;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 故意不使用外层 @Transactional：两个真实请求必须各自提交，才能验证并发唯一约束。 */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class BehaviorEventConcurrencyTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Test void 两个并发请求只插入一次且不返回500() throws Exception {
        var f = BehaviorTestFixtures.create(jdbc, null);
        UUID event = UUID.randomUUID();
        String body = """
                {"eventId":"%s","schemaVersion":1,"targetType":"PLACE","provider":"LOCAL",
                 "targetId":"%d","recommendationId":%d,"action":"NAVIGATION_REQUESTED","occurredAt":"%s"}
                """.formatted(event, f.placeId(), f.recommendationId(), Instant.now());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> call = () -> {
            start.await();
            return mvc.perform(post("/api/travel/sessions/{id}/behavior-events", f.sessionId())
                    .with(csrf()).header("X-Session-Token", f.token().toString())
                    .contentType("application/json").content(body)).andReturn().getResponse().getStatus();
        };
        try {
            Future<Integer> a = workers.submit(call), b = workers.submit(call); start.countDown();
            var statuses = new java.util.ArrayList<>(java.util.List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)));
            statuses.sort(Integer::compareTo);
            assertEquals(java.util.List.of(200, 201), statuses);
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM behavior_events WHERE event_id=?", Integer.class, event));
        } finally {
            workers.shutdownNow();
            // 只清理本测试创建的会话。其他测试/开发数据不受影响。
            jdbc.update("DELETE FROM test_sessions WHERE id=?", f.sessionId());
        }
    }
}
