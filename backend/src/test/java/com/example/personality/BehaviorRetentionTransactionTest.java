package com.example.personality;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BehaviorRetentionTransactionTest extends BehaviorRetentionServiceIntegrationTest {
    @MockitoSpyBean JdbcTemplate jdbcSpy;
    @Test void 来源删除失败时事件清理也回滚而不是半清理() throws Throwable {
        var body=navigation(); send(body).andExpect(status().isCreated());
        Instant now=Instant.now(); UUID batch=batch(fixture,now.minusSeconds(1));
        jdbc.update("UPDATE behavior_events SET received_at=? WHERE event_id=?",java.sql.Timestamp.from(now.minusSeconds(91*86400L)),UUID.fromString(body.path("eventId").asString()));
        doThrow(new DataIntegrityViolationException("测试注入来源删除失败")).when(jdbcSpy).update("DELETE FROM restaurant_search_batches WHERE result_set_id=?",batch);
        assertThrows(DataIntegrityViolationException.class,()->purge(now,100));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM behavior_events WHERE session_id=?",Integer.class,fixture.sessionId()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM restaurant_search_items WHERE result_set_id=?",Integer.class,batch));
    }
}
