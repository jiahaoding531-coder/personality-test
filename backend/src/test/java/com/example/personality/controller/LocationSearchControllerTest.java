package com.example.personality.controller;

import com.example.personality.domain.AccessScope;
import com.example.personality.dto.*;
import com.example.personality.exception.*;
import com.example.personality.service.*;
import com.example.personality.security.*;
import com.example.personality.entity.*;
import com.example.personality.repository.TestSessionRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LocationSearchControllerTest {
    final BehaviorAccessService access=mock(BehaviorAccessService.class);
    final AMapLocationSearchService search=mock(AMapLocationSearchService.class);
    @SuppressWarnings("unchecked") final ObjectProvider<AMapLocationSearchService> provider=mock(ObjectProvider.class);
    MockMvc mvc;
    final String path="/api/travel/sessions/42/locations/search";
    @AfterEach void clearIdentity(){SecurityContextHolder.clearContext();}
    @BeforeEach void setup() {
        when(access.requireAccess(anyLong(),any(),any())).thenReturn(new AccessScope(42L,null));
        when(provider.getIfAvailable()).thenReturn(search);
        mvc=MockMvcBuilders.standaloneSetup(new LocationSearchController(access,provider))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @Test void 返回精简候选并先校验归属() throws Exception {
        when(search.search(any())).thenReturn(new LocationSearchResponse(List.of(new LocationCandidate(
                "A","西湖","西湖区","浙江省","杭州市","西湖区",30.24,120.14,"GCJ02","AMAP_POI"))));
        mvc.perform(post(path).header("X-Session-Token","token").contentType(MediaType.APPLICATION_JSON).content("{\"keyword\":\"西湖\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.candidates[0].coordinateSystem").value("GCJ02"))
                .andExpect(jsonPath("$.candidates[0].source").value("AMAP_POI"));
        var order=inOrder(access,search);
        order.verify(access).requireAccess(42L,"token",null);
        order.verify(search).search(any());
    }
    @Test void 越权不触发外部调用() throws Exception {
        when(access.requireAccess(anyLong(),any(),any())).thenThrow(new ResourceNotFoundException("会话不存在"));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"keyword\":\"西湖\"}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(provider,search);
    }
    @Test void 非法输入与未配置服务不能返回假候选() throws Exception {
        for(String word:List.of("", " ", "字".repeat(81)))
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"keyword\":\""+word+"\"}"))
                    .andExpect(status().isBadRequest());
        verifyNoInteractions(search);
        when(provider.getIfAvailable()).thenReturn(null);
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"keyword\":\"西湖\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AMAP_NOT_CONFIGURED"));
    }
    @Test void 真实归属规则拒绝其他账号旧令牌和跨会话令牌() throws Exception {
        var repo=mock(TestSessionRepository.class);
        var session=TestSession.start(1L,QuestionScale.TRAVEL);
        var another=TestSession.start(null,QuestionScale.TRAVEL);
        when(repo.findById(42L)).thenReturn(Optional.of(session));
        when(repo.findById(43L)).thenReturn(Optional.of(another));
        mvc=MockMvcBuilders.standaloneSetup(new LocationSearchController(new BehaviorAccessService(new SessionAccessGuard(repo)),provider))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var other=new AppUserPrincipal(2L,"测试用户","");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(other,null,other.getAuthorities()));
        mvc.perform(post(path).header("X-Session-Token",session.getAccessToken().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"keyword\":\"西湖\"}"))
                .andExpect(status().isNotFound());
        SecurityContextHolder.clearContext();
        mvc.perform(post("/api/travel/sessions/43/locations/search").header("X-Session-Token",session.getAccessToken().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"keyword\":\"西湖\"}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(search);
        when(search.search(any())).thenReturn(new LocationSearchResponse(List.of()));
        var owner=new AppUserPrincipal(1L,"测试本人","");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner,null,owner.getAuthorities()));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"keyword\":\"西湖\"}"))
                .andExpect(status().isOk());
    }
}
