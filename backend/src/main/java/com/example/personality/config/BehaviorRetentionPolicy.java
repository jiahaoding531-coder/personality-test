package com.example.personality.config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record BehaviorRetentionPolicy(int eventDays,int snapshotDays,int batchSize) {
    public BehaviorRetentionPolicy(@Value("${app.behavior-retention.event-days:90}") int eventDays,
            @Value("${app.behavior-retention.snapshot-days:7}") int snapshotDays,
            @Value("${app.behavior-retention.batch-size:500}") int batchSize) {
        if(snapshotDays<7 || eventDays<snapshotDays || eventDays>3650 || batchSize<1 || batchSize>1000)
            throw new IllegalArgumentException("行为留存配置不合法：快照至少 7 天、事件不短于快照、分块 1 至 1000 条");
        this.eventDays=eventDays; this.snapshotDays=snapshotDays; this.batchSize=batchSize;
    }
}
