package com.example.personality.ai;

import com.example.personality.config.AiSafetyProperties;
import com.example.personality.exception.AiRequestRejectedException;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiCallGuardTest {
    final AiBudgetStore store = mock(AiBudgetStore.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-10-13T10:00:00Z"), ZoneOffset.UTC);
    AiCallGuard guard(int rate, int concurrent) {
        return new AiCallGuard(new AiSafetyProperties(rate,concurrent,100,300,200000,1000000,20000,1024),clock,store);
    }
    @Test void 同一用户在途请求拒绝重复付费() {
        var guard = guard(6,2);
        try (var lease = guard.begin(1L,2000,true)) {
            assertThrows(AiRequestRejectedException.class, () -> guard.begin(1L,2000,true));
        }
        assertDoesNotThrow(() -> guard.begin(1L,2000,true).close());
    }
    @Test void 全局并发达到上限即拒绝而不是排队() {
        var guard = guard(6,1);
        try (var lease = guard.begin(1L,2000,true)) {
            assertThrows(AiRequestRejectedException.class, () -> guard.begin(2L,2000,true));
        }
        assertDoesNotThrow(() -> guard.begin(2L,2000,true).close());
    }
    @Test void 完成请求不绕过每分钟限频() {
        var guard = guard(2,2);
        guard.begin(1L,2000,true).close(); guard.begin(1L,2000,true).close();
        var ex = assertThrows(AiRequestRejectedException.class, () -> guard.begin(1L,2000,true));
        assertEquals("AI_RATE_LIMITED",ex.code());
    }
    @Test void 预算拒绝不泄漏并发名额且不发起模型调用() {
        var guard = guard(6,1);
        doThrow(new AiRequestRejectedException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                "AI_BUDGET_EXHAUSTED","预算已达上限")).doNothing().when(store).reserve(any(),any(),anyLong());
        assertThrows(AiRequestRejectedException.class, () -> guard.begin(1L,2000,true));
        assertDoesNotThrow(() -> guard.begin(1L,2000,true).close());
    }
    @Test void 并发争抢同一用户只有一个成功() throws Exception {
        var guard = guard(6,2);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var future = pool.submit(() -> { try (var lease=guard.begin(1L,2000,true)) {
                started.countDown(); release.await(2,TimeUnit.SECONDS);
            } return true; });
            assertTrue(started.await(2,TimeUnit.SECONDS));
            assertThrows(AiRequestRejectedException.class, () -> guard.begin(1L,2000,true));
            release.countDown(); assertTrue(future.get(3,TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
