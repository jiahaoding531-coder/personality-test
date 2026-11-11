package com.example.personality.ai;

import com.example.personality.config.AiSafetyProperties;
import com.example.personality.exception.AiRequestRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;

/** 单行条件 UPDATE 等价于 JDBC 事务中的原子预算扣减，重启后不会重置累计额度。 */
@Component
public class AiBudgetStore {
    private final JdbcTemplate jdbc;
    public AiBudgetStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    // 独立短事务先提交预留，再调用外网；超时可能已产生费用，因此不盲目退还。
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void reserve(AiSafetyProperties limits, LocalDate day, long tokens) {
        int changed=jdbc.update("""
            UPDATE ai_usage_budget SET
              daily_requests = CASE WHEN budget_day < ? THEN 1 ELSE daily_requests + 1 END,
              daily_reserved_tokens = CASE WHEN budget_day < ? THEN ? ELSE daily_reserved_tokens + ? END,
              budget_day = ?, total_requests = total_requests + 1,
              total_reserved_tokens = total_reserved_tokens + ?
            WHERE id=1 AND budget_day <= ?
              AND total_requests < ? AND total_reserved_tokens <= ? - ?
              AND (CASE WHEN budget_day < ? THEN 0 ELSE daily_requests END) < ?
              AND (CASE WHEN budget_day < ? THEN 0 ELSE daily_reserved_tokens END) <= ? - ?
            """, day,day,tokens,tokens,day,tokens,day,
                limits.maxTotalRequests(),limits.maxTotalReservedTokens(),tokens,
                day,limits.maxDailyRequests(),day,limits.maxDailyReservedTokens(),tokens);
        if(changed != 1) throw new AiRequestRejectedException(HttpStatus.TOO_MANY_REQUESTS,
                "AI_BUDGET_EXHAUSTED","AI 调用额度已达上限，请稍后或联系演示负责人");
    }
}
