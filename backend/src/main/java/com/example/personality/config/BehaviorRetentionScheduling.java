package com.example.personality.config;
import com.example.personality.service.BehaviorRetentionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import java.time.Clock;

/** 每日最多十个小事务，避免一次清理长期占用连接；下一日继续处理剩余到期数据。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name="app.behavior-retention.cleanup-enabled",havingValue="true",matchIfMissing=true)
public class BehaviorRetentionScheduling {
    private final BehaviorRetentionService retention;
    private final BehaviorRetentionPolicy policy;
    private final Clock clock;
    public BehaviorRetentionScheduling(BehaviorRetentionService retention,BehaviorRetentionPolicy policy,Clock clock) { this.retention=retention; this.policy=policy; this.clock=clock; }
    @Scheduled(cron="${app.behavior-retention.cleanup-cron:0 20 3 * * *}",zone="Asia/Shanghai")
    public void cleanup() {
        for(int i=0;i<10;i++) {
            var counts=retention.purgeExpired(clock.instant(),policy.batchSize());
            if(counts.events()<policy.batchSize() && counts.batches()<policy.batchSize()) break;
        }
    }
}
