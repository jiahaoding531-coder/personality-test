package com.example.personality;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BehaviorClockIntegrationTest extends BehaviorEventHttpTestBase {
    @MockitoSpyBean Clock clock;
    @Test void 接收时间由服务端Clock决定且已有历史事件不重新校验首次时间() throws Exception {
        Instant initial=Instant.parse("2026-10-01T00:00:00Z"); when(clock.instant()).thenReturn(initial);
        var body=navigation().put("occurredAt",initial.toString()); send(body).andExpect(status().isCreated());
        assertEquals(initial,jdbc.queryForObject("SELECT received_at FROM behavior_events WHERE event_id=?",java.sql.Timestamp.class,
                UUID.fromString(body.path("eventId").asString())).toInstant());
        when(clock.instant()).thenReturn(initial.plus(Duration.ofDays(8)));
        send(body).andExpect(status().isOk());
        send(body.deepCopy().put("eventId",UUID.randomUUID().toString())).andExpect(status().isBadRequest());
    }
}
