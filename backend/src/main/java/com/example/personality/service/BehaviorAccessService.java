package com.example.personality.service;
import com.example.personality.domain.AccessScope;
import com.example.personality.exception.BehaviorRequestException;
import com.example.personality.security.*;
import org.springframework.stereotype.Service;

@Service
public class BehaviorAccessService {
    private final SessionAccessGuard guard;
    public BehaviorAccessService(SessionAccessGuard guard) { this.guard = guard; }
    public AccessScope requireAccess(Long sessionId, String token, AppUserPrincipal principal) {
        var session = guard.requireAccess(sessionId, SessionAccessGuard.parseToken(token), principal);
        // 已登录归属的会话不得由另一个账号携带旧令牌写入新行为。
        if (!"TRAVEL".equals(session.getScale().name()) || (session.getUserId() != null
                && (principal == null || !session.getUserId().equals(principal.getId())))) throw BehaviorRequestException.missing();
        return new AccessScope(sessionId, session.getUserId());
    }
}
