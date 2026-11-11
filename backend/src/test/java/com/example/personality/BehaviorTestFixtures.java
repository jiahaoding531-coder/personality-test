package com.example.personality;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;

/** 测试夹具只创建自身会话和推荐，不接触真实用户数据。 */
final class BehaviorTestFixtures {
    record Fixture(long sessionId, UUID token, long recommendationId, long placeId) {}

    static Fixture create(JdbcTemplate jdbc, Long userId) {
        UUID token = UUID.randomUUID();
        Long sessionId = jdbc.queryForObject("""
                INSERT INTO test_sessions(user_id, access_token, scale)
                VALUES (?, ?, 'TRAVEL') RETURNING id
                """, Long.class, userId, token);
        Long placeId = jdbc.queryForObject("SELECT id FROM places ORDER BY id LIMIT 1", Long.class);
        Long recommendationId = jdbc.queryForObject("""
                INSERT INTO recommendations(session_id, batch_no, rank_no, place_id, score)
                VALUES (?, 1, 1, ?, 0.5) RETURNING id
                """, Long.class, sessionId, placeId);
        jdbc.update("""
                INSERT INTO recommendation_batches(session_id, batch_no, context_time,
                    remaining_minutes, max_distance_km) VALUES (?, 1, '12:00', 240, 10)
                """, sessionId);
        return new Fixture(sessionId, token, recommendationId, placeId);
    }
    private BehaviorTestFixtures() {}
}
