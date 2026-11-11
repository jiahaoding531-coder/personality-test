package com.example.personality.controller;

import com.example.personality.ai.AiCredentialsResolver;
import com.example.personality.config.AiProperties;
import com.example.personality.dto.InterpretRequest;
import com.example.personality.entity.TestSession;
import com.example.personality.exception.BusinessException;
import com.example.personality.repository.TestSessionRepository;
import com.example.personality.security.AppUserPrincipal;
import com.example.personality.security.SessionAccessGuard;
import com.example.personality.service.TravelIntentService;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiAccessSafetyTest {
    final TestSessionRepository repository = mock(TestSessionRepository.class);
    final TravelIntentService intents = mock(TravelIntentService.class);
    final TestSession session = mock(TestSession.class);
    final String token = UUID.randomUUID().toString();
    TravelController controller(Long owner) {
        when(repository.findById(42L)).thenReturn(Optional.of(session));
        when(session.getUserId()).thenReturn(owner);
        when(session.matchesToken(any())).thenReturn(true);
        var properties = new AiProperties();
        properties.setEnabled(true); properties.setApiKey("test-only-not-a-real-key");
        return new TravelController(null,null,null,null,null,intents,null,
                new AiCredentialsResolver(properties),new SessionAccessGuard(repository),null,null);
    }
    @Test void 匿名持有会话令牌仍不能调用付费AI() {
        var controller = controller(7L);
        var ex = assertThrows(BusinessException.class, () -> controller.interpret(42L, token, null,
                new InterpretRequest("想安静一点"),null,null));
        assertEquals(401, ex.status().value());
        verifyNoInteractions(intents);
    }
    @Test void 其他登录用户持有旧令牌不能调用别人的AI() {
        var controller = controller(7L);
        var ex = assertThrows(BusinessException.class, () -> controller.interpret(42L, token,
                new AppUserPrincipal(8L,"测试用户",""),new InterpretRequest("想安静一点"),null,null));
        assertEquals(404, ex.status().value());
        verifyNoInteractions(intents);
    }
    @Test void 登录用户不能借匿名会话消耗共享Key() {
        var controller = controller(null);
        assertThrows(BusinessException.class, () -> controller.interpret(42L, token,
                new AppUserPrincipal(7L,"测试用户",""),new InterpretRequest("想安静一点"),null,null));
        verifyNoInteractions(intents);
    }
    @Test void 本人登录会话保持可用() {
        var controller = controller(7L);
        assertDoesNotThrow(() -> controller.interpret(42L,null,
                new AppUserPrincipal(7L,"测试用户",""),new InterpretRequest("想安静一点"),null,null));
        verify(intents).interpret(any(),eq("想安静一点"));
    }
}
