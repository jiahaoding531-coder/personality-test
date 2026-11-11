package com.example.personality.domain;

/** 仅由已经通过会话权限校验的服务创建，userId 不从请求体获取。 */
public record AccessScope(Long sessionId, Long userId) {}
