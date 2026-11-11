package com.example.personality.service;

import com.example.personality.domain.*;
import com.example.personality.exception.BehaviorRequestException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;

/** 参数化 JDBC 明确展示原子写入：依靠主键冲突，不依赖 JVM 单机锁或先查再插。 */
@Repository
public class BehaviorEventStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    public BehaviorEventStore(JdbcTemplate jdbc,ObjectMapper mapper,Clock clock) { this.jdbc=jdbc; this.mapper=mapper; this.clock=clock; }
    public boolean existing(AccessScope scope,BehaviorEventCommand command) {
        var rows=jdbc.query("SELECT session_id,user_id,canonical_payload::text FROM behavior_events WHERE event_id=?",
                (rs,n)->new Stored(rs.getLong(1),(Long)rs.getObject(2),rs.getString(3)),command.eventId());
        if(rows.isEmpty()) return false;
        var row=rows.get(0);
        if(!Objects.equals(row.sessionId(),scope.sessionId()) || !Objects.equals(row.userId(),scope.userId()))
            throw BehaviorRequestException.missing();
        // JSONB 会重排字段、重新编码数字；还原为强类型命令再比较，避免 Long/Integer 节点假冲突。
        if(!mapper.readValue(row.payload(),BehaviorEventCommand.class).equals(command)) throw BehaviorRequestException.conflict();
        return true;
    }
    public boolean insert(AccessScope scope,BehaviorEventCommand c) {
        String json=mapper.writeValueAsString(c);
        var inserted=jdbc.query("""
                INSERT INTO behavior_events(event_id,user_id,session_id,target_type,provider,target_id,action,occurred_at,
                    schema_version,recommendation_id,recommendation_batch_no,search_session_id,result_set_id,reaction,
                    visible_sources,canonical_payload,request_hash,received_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?)
                ON CONFLICT(event_id) DO NOTHING RETURNING event_id
                """,(rs,n)->rs.getObject(1),c.eventId(),scope.userId(),scope.sessionId(),c.targetType().name(),c.provider().name(),
                c.targetId(),c.action().name(),Timestamp.from(c.occurredAt()),c.schemaVersion(),c.recommendationId(),
                c.recommendationBatchNo(),c.searchSessionId(),c.resultSetId(),c.reaction(),mapper.writeValueAsString(c.visibleSources()),json,hash(json),Timestamp.from(clock.instant()));
        if(!inserted.isEmpty()) return true;
        // PostgreSQL READ COMMITTED：冲突插入等待对手提交；下一条查询能够看到已提交的内容。
        existing(scope,c);
        return false;
    }
    private static String hash(String json) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("当前 JDK 缺少 SHA-256",impossible); }
    }
    private record Stored(Long sessionId,Long userId,String payload) {}
}
