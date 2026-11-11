package com.example.personality.service;
import com.example.personality.config.BehaviorRetentionPolicy;
import com.example.personality.dto.CleanupCounts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** 仅维护本批三表，绝不清除旧反馈/画像；没有公共 HTTP 路由。 */
@Service
public class BehaviorRetentionService {
    private final JdbcTemplate jdbc;
    private final BehaviorRetentionPolicy policy;
    public BehaviorRetentionService(JdbcTemplate jdbc,BehaviorRetentionPolicy policy) { this.jdbc=jdbc; this.policy=policy; }
    @Transactional
    public CleanupCounts purgeExpired(Instant now,int batchSize) {
        if(now==null || batchSize<1 || batchSize>1000) throw new IllegalArgumentException("清理分块必须为 1 至 1000 条");
        var events=jdbc.query("""
                WITH expired AS (SELECT event_id FROM behavior_events WHERE received_at<=?
                    ORDER BY received_at,event_id LIMIT ? FOR UPDATE SKIP LOCKED)
                DELETE FROM behavior_events WHERE event_id IN (SELECT event_id FROM expired) RETURNING event_id
                """,(rs,n)->rs.getObject(1),Timestamp.from(now.minus(Duration.ofDays(policy.eventDays()))),batchSize);
        var batches=jdbc.query("""
                SELECT result_set_id FROM restaurant_search_batches WHERE expires_at<=?
                ORDER BY expires_at,result_set_id LIMIT ? FOR UPDATE SKIP LOCKED
                """,(rs,n)->rs.getObject(1,UUID.class),Timestamp.from(now),batchSize);
        return deleteBatches(events.size(),batches);
    }
    @Transactional
    public CleanupCounts deleteNewBehaviorDataForUser(Long userId) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        // 维护身份只允许由可信内部调用者设定；普通登录角色不能使用，不能从请求体传入。
        if(auth==null || !auth.isAuthenticated() || auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_INTERNAL_DATA_MAINTAINER")))
            throw new AccessDeniedException("需要内部数据维护身份");
        if(userId==null || userId<=0) throw new IllegalArgumentException("必须指定有效用户");
        // 锁住用户行，让本服务的相同用户删除串行执行，不接受任意 SQL 或全库操作。
        if(jdbc.query("SELECT id FROM users WHERE id=? FOR UPDATE",(rs,n)->rs.getLong(1),userId).isEmpty())
            throw new IllegalArgumentException("指定用户不存在");
        long events=jdbc.update("DELETE FROM behavior_events WHERE user_id=?",userId);
        var batches=jdbc.query("SELECT result_set_id FROM restaurant_search_batches WHERE user_id=? FOR UPDATE",(rs,n)->rs.getObject(1,UUID.class),userId);
        return deleteBatches(events,batches);
    }
    private CleanupCounts deleteBatches(long events,List<UUID> batches) {
        long items=0;
        for(UUID id:batches) {
            items+=jdbc.queryForObject("SELECT count(*) FROM restaurant_search_items WHERE result_set_id=?",Long.class,id);
            // 仅页→条目外键级联；行为事件无此外键，因此保留目标及来源标识。
            jdbc.update("DELETE FROM restaurant_search_batches WHERE result_set_id=?",id);
        }
        return new CleanupCounts(events,items,batches.size());
    }
}
