package com.example.personality.domain;
import com.example.personality.exception.BehaviorRequestException;
import java.time.*;
import java.util.UUID;

/** 旧接口无头时由服务生成身份；新客户端重试必须复用这两个头。 */
public record BehaviorMetadata(UUID eventId,Instant occurredAt,Long expectedFeedbackVersion) {
    public BehaviorMetadata(UUID eventId,Instant occurredAt) { this(eventId,occurredAt,null); }
    public static BehaviorMetadata parse(String id,String time) {
        return parse(id,time,null);
    }
    public static BehaviorMetadata parse(String id,String time,String version) {
        if(version!=null && (id==null || time==null)) throw BehaviorRequestException.invalid();
        if(id==null && time==null) return null;
        if(id==null || time==null) throw BehaviorRequestException.invalid();
        try {
            Long expected=version==null?null:Long.valueOf(version);
            if(expected!=null && expected<0) throw BehaviorRequestException.invalid();
            return new BehaviorMetadata(UUID.fromString(id),Instant.parse(time),expected);
        }
        catch(IllegalArgumentException | DateTimeException invalid) { throw BehaviorRequestException.invalid(); }
    }
}
