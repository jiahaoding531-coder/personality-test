package com.example.personality.service;
import com.example.personality.domain.*;
import com.example.personality.exception.BehaviorRequestException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;

@Component
public class BehaviorTargetValidator {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public BehaviorTargetValidator(JdbcTemplate jdbc, Clock clock) { this.jdbc=jdbc; this.clock=clock; }
    public void validateScope(AccessScope scope) {
        // 与内部删除保持同样顺序：用户→会话→来源，防止双方相互等待。
        if(scope.userId()!=null && jdbc.query("SELECT id FROM users WHERE id=? FOR KEY SHARE",
                (rs,n)->rs.getLong(1),scope.userId()).isEmpty()) throw BehaviorRequestException.missing();
        var owners = jdbc.query("SELECT user_id FROM test_sessions WHERE id=? AND scale='TRAVEL' FOR KEY SHARE",
                (rs,n) -> (Long) rs.getObject(1), scope.sessionId());
        if (owners.size()!=1 || !Objects.equals(owners.get(0),scope.userId())) throw BehaviorRequestException.missing();
    }
    public void validate(AccessScope scope, BehaviorEventCommand command) {
        if (command.provider()==BehaviorProvider.LOCAL) {
            if (command.recommendationId()!=null) local(scope,command.recommendationId(),command.targetId(),command.recommendationBatchNo());
            if (command.recommendationBatchNo()!=null && !Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM recommendation_batches WHERE session_id=? AND batch_no=?)",
                    Boolean.class,scope.sessionId(),command.recommendationBatchNo()))) throw BehaviorRequestException.missing();
        } else restaurant(scope,command.searchSessionId(),command.resultSetId(),command.targetId());
        for (var source:command.visibleSources()) {
            if (command.provider()==BehaviorProvider.LOCAL) local(scope,source.recommendationId(),source.targetId(),command.recommendationBatchNo());
            else restaurant(scope,source.searchSessionId(),source.resultSetId(),source.targetId());
        }
    }
    private void local(AccessScope scope, Long id, String target, Integer batch) {
        // 不只分别验证“批次存在”和“卡片存在”：它们还必须属于同一会话的同一批。
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM recommendations WHERE id=? AND session_id=? AND place_id::text=?
                  AND (?::integer IS NULL OR batch_no=?))
                """,
                Boolean.class,id,scope.sessionId(),target,batch,batch))) throw BehaviorRequestException.missing();
    }
    private void restaurant(AccessScope scope, UUID search, UUID result, String poi) {
        // 和 JDBC 的事务行锁相同：清理必须等本次验证及事件插入提交后才能删除来源页。
        var batches=jdbc.query("""
                SELECT result_set_id FROM restaurant_search_batches
                WHERE result_set_id=? AND search_session_id=? AND session_id=?
                  AND user_id IS NOT DISTINCT FROM ? AND expires_at>? FOR KEY SHARE
                """,(rs,n)->rs.getObject(1),result,search,scope.sessionId(),scope.userId(),Timestamp.from(clock.instant()));
        if(batches.isEmpty()) throw BehaviorRequestException.missing();
        if(poi!=null && !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM restaurant_search_items WHERE result_set_id=? AND poi_id=?)",
                Boolean.class,result,poi))) throw BehaviorRequestException.missing();
    }
}
