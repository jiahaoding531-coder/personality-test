package com.example.personality;

import com.example.personality.ai.AiBudgetStore;
import com.example.personality.config.AiSafetyProperties;
import com.example.personality.exception.AiRequestRejectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 PostgreSQL 条件更新；不调模型，只在独立测试库预留额度。 */
@SpringBootTest
@ActiveProfiles("test")
class AiBudgetIntegrationTest {
    @Autowired AiBudgetStore store;
    @Autowired JdbcTemplate jdbc;
    final LocalDate day = LocalDate.of(2026, 10, 13);

    @BeforeEach void 初始化隔离测试额度() {
        assertTrue(jdbc.queryForObject("SELECT current_database()", String.class).contains("test"));
        jdbc.update("UPDATE ai_usage_budget SET budget_day=?,daily_requests=0,daily_reserved_tokens=0,total_requests=0,total_reserved_tokens=0 WHERE id=1", day);
    }

    @Test void 并发预留不能突破请求上限() throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i=0;i<12;i++) futures.add(executor.submit(() -> {
                try { store.reserve(limits(3,3,100,100), day, 7); return true; }
                catch (AiRequestRejectedException ex) { assertEquals("AI_BUDGET_EXHAUSTED", ex.code()); return false; }
            }));
            int accepted=0;
            for (var future:futures) if (future.get(10,TimeUnit.SECONDS)) accepted++;
            assertEquals(3, accepted);
            assertEquals(3L, count("total_requests"));
            assertEquals(21L, count("total_reserved_tokens"));
        } finally { executor.shutdownNow(); }
    }
    @Test void 达到Token预算后拒绝且不改变计数() {
        store.reserve(limits(10,10,10,10),day,10);
        assertThrows(AiRequestRejectedException.class,()->store.reserve(limits(10,10,10,10),day,1));
        assertEquals(1L,count("total_requests"));
        assertEquals(10L,count("total_reserved_tokens"));
    }
    @Test void 新日期只重置每日额度累计额度不重置() {
        var limits=limits(1,2,10,20);
        store.reserve(limits,day,10);
        assertThrows(AiRequestRejectedException.class,()->store.reserve(limits,day,1));
        store.reserve(limits,day.plusDays(1),10);
        assertEquals(1L,count("daily_requests"));
        assertEquals(2L,count("total_requests"));
        assertThrows(AiRequestRejectedException.class,()->store.reserve(limits,day.plusDays(2),1));
    }
    @Test void 新建服务对象仍然读取已消耗累计额度() {
        var limits=limits(5,1,100,100);
        store.reserve(limits,day,10);
        var restartedStore=new AiBudgetStore(jdbc);
        assertThrows(AiRequestRejectedException.class,()->restartedStore.reserve(limits,day,10));
        assertEquals(1L,count("total_requests"));
    }
    @Test void 零额度和时钟倒退均不能绕过限制() {
        assertThrows(AiRequestRejectedException.class,()->store.reserve(limits(0,0,0,0),day,1));
        assertThrows(AiRequestRejectedException.class,()->store.reserve(limits(5,5,100,100),day.minusDays(1),1));
        assertEquals(0L,count("total_requests"));
    }
    long count(String column) {
        // 列名只来自本测试的固定字面量，不接受外部输入。
        return jdbc.queryForObject("SELECT "+column+" FROM ai_usage_budget WHERE id=1",Long.class);
    }
    AiSafetyProperties limits(long daily,long total,long dailyTokens,long totalTokens) {
        return new AiSafetyProperties(6,2,daily,total,dailyTokens,totalTokens,20000,1024);
    }
}
