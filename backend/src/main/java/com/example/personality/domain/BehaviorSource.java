package com.example.personality.domain;
import java.util.UUID;
/** 列表级动作最多关联三张已展示卡片，各卡来源独立验证。 */
public record BehaviorSource(Long recommendationId, UUID searchSessionId, UUID resultSetId, String targetId) {}
