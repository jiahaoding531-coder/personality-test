package com.example.personality;

import org.junit.jupiter.api.Test;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import java.time.Instant;

/** 删除契约白名单或时间边界检查，会允许虚构字段和未经实现的动作进入事实表。 */
class BehaviorEventContractTest extends BehaviorEventHttpTestBase {
    @Test void 不接受伪造用户隐私字段和未实现的行为() throws Exception {
        for (String key : new String[]{"userId", "latitude", "token", "metadata"})
            send(navigation().put(key, "不应接收")).andExpect(status().isBadRequest());
        for (String action : new String[]{"IMPRESSION", "VISIT_CONFIRMED", "REACTION_SET", "REACTION_CLEAR"})
            send(navigation().put("action", action)).andExpect(status().isBadRequest());
        send(navigation().put("targetType", "HOTEL")).andExpect(status().isBadRequest());
        send(navigation().put("schemaVersion", 2)).andExpect(status().isBadRequest());
        send(navigation().putNull("eventId")).andExpect(status().isBadRequest());
        send(navigation().put("action", "EXCLUDE")).andExpect(status().isBadRequest());
    }
    @Test void 首次事件拒绝过期或超前时间() throws Exception {
        send(navigation().put("occurredAt", Instant.now().minusSeconds(7 * 86400L + 5).toString()))
                .andExpect(status().isBadRequest());
        send(navigation().put("occurredAt", Instant.now().plusSeconds(310).toString()))
                .andExpect(status().isBadRequest());
    }
    @Test void 已保存事件不会因变旧而失去幂等性() throws Exception {
        var body = navigation(); send(body).andExpect(status().isCreated());
        // 调整服务端接收时间不能改变相同事件的内容比较结果。
        jdbc.update("UPDATE behavior_events SET received_at=CURRENT_TIMESTAMP - INTERVAL '8 days' WHERE event_id=?",
                java.util.UUID.fromString(body.path("eventId").asString()));
        send(body).andExpect(status().isOk());
    }
    @Test void 缺少CSRF不能记录() throws Exception {
        mockMvc.perform(post("/api/travel/sessions/{id}/behavior-events", fixture.sessionId())
                .header("X-Session-Token", fixture.token().toString()).contentType("application/json")
                .content(navigation().toString())).andExpect(status().isForbidden());
    }
}
