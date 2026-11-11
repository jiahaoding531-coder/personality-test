package com.example.personality.ai;

import com.example.personality.config.AiSafetyProperties;
import com.example.personality.exception.AiRequestRejectedException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 无等待队列：同一用户只允许一个在途调用，公共额度在真实 HTTP 调用前扣减。 */
@Component
@EnableConfigurationProperties(AiSafetyProperties.class)
public class AiCallGuard {
    private final AiSafetyProperties limits;
    private final Clock clock;
    private final AiBudgetStore store;
    private final Map<Long, Window> windows = new HashMap<>();
    private final Set<Long> active = new HashSet<>();
    public AiCallGuard(AiSafetyProperties limits,Clock clock,AiBudgetStore store) {
        this.limits=limits; this.clock=clock; this.store=store;
    }
    public int maxPromptCharacters() { return limits.maxPromptCharacters(); }
    public synchronized Lease begin(Long userId,long reservedTokens,boolean serverPaid) {
        if(userId==null) throw reject(HttpStatus.UNAUTHORIZED,"AI_LOGIN_REQUIRED","请登录后使用 AI");
        if(reservedTokens<=0) throw reject(HttpStatus.BAD_REQUEST,"AI_INVALID_REQUEST","AI 请求长度不合法");
        long minute=clock.instant().getEpochSecond()/60;
        windows.entrySet().removeIf(e -> e.getValue().minute != minute && !active.contains(e.getKey()));
        Window current=windows.get(userId);
        if(active.contains(userId) || active.size()>=limits.maxConcurrent())
            throw reject(HttpStatus.TOO_MANY_REQUESTS,"AI_BUSY","AI 正在处理请求，请等待后重试");
        if(current!=null && current.minute==minute && current.calls>=limits.perUserPerMinute())
            throw reject(HttpStatus.TOO_MANY_REQUESTS,"AI_RATE_LIMITED","AI 请求过于频繁，请稍后重试");
        if(current==null && windows.size()>=limits.maxTrackedUsers())
            throw reject(HttpStatus.TOO_MANY_REQUESTS,"AI_BUSY","AI 服务繁忙，请稍后重试");
        if(serverPaid) {
            try { store.reserve(limits,LocalDate.ofInstant(clock.instant(),ZoneOffset.UTC),reservedTokens); }
            catch(AiRequestRejectedException ex) { throw ex; }
            catch(RuntimeException ex) {
                // 预算存储不可用必须关闭付费入口，不能绕过保护继续花钱。
                throw reject(HttpStatus.SERVICE_UNAVAILABLE,"AI_BUDGET_UNAVAILABLE","暂时无法核验 AI 额度，请稍后重试");
            }
        }
        windows.put(userId,new Window(minute,current!=null && current.minute==minute?current.calls+1:1));
        active.add(userId);
        return new Lease(userId);
    }
    private static AiRequestRejectedException reject(HttpStatus status,String code,String message) {
        return new AiRequestRejectedException(status,code,message);
    }
    private record Window(long minute,int calls) { }
    public final class Lease implements AutoCloseable {
        private final Long userId;
        private final AtomicBoolean closed=new AtomicBoolean();
        private Lease(Long userId) { this.userId=userId; }
        @Override public void close() {
            if(closed.compareAndSet(false,true)) synchronized(AiCallGuard.this) { active.remove(userId); }
        }
    }
}
