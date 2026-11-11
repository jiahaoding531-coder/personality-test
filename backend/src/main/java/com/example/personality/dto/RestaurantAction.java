package com.example.personality.dto;
import java.time.Instant;
import java.util.UUID;

/** 未来事件接口的值契约；本阶段不开放写入端点，也不虚报已记录行为。 */
public record RestaurantAction(String poiId, UUID searchSessionId, UserAction userAction, Instant timestamp) {
    public enum UserAction { IMPRESSION, NAVIGATION, DISMISS }
}
