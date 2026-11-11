package com.example.personality.controller;

import com.example.personality.dto.*;
import com.example.personality.exception.*;
import com.example.personality.service.BehaviorAccessService;
import com.example.personality.service.RestaurantSearchOrchestrator;
import com.example.personality.domain.AccessScope;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RestaurantControllerTest {
    final BehaviorAccessService guard=mock(BehaviorAccessService.class);
    final RestaurantSearchOrchestrator service=mock(RestaurantSearchOrchestrator.class);
    MockMvc mvc;
    final String path="/api/travel/sessions/42/restaurants/search";
    final String body="{\"latitude\":33.735,\"longitude\":113.3077,\"coordinateSystem\":\"WGS84\"}";
    @BeforeEach void setup(){
        when(guard.requireAccess(anyLong(),any(),any())).thenReturn(new AccessScope(42L,null));
        mvc=MockMvcBuilders.standaloneSetup(new RestaurantController(guard,service))
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @Test void 合法请求返回独立餐饮响应() throws Exception {
        when(service.search(any(),any())).thenReturn(new RestaurantSearchResponse(UUID.randomUUID(),Instant.now(),
            "NO_RESULTS",3000,"GCJ02","STRAIGHT_LINE",List.of()));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.restaurants").isEmpty())
            .andExpect(jsonPath("$.distanceType").value("STRAIGHT_LINE"))
            .andExpect(jsonPath("$.searchSessionId").exists());
        var ordered=inOrder(guard,service);
        ordered.verify(guard).requireAccess(eq(42L),isNull(),isNull());
        ordered.verify(service).search(any(),any());
    }
    @Test void 越权不调用高德服务() throws Exception {
        doThrow(new ResourceNotFoundException("会话不存在")).when(guard).requireAccess(anyLong(),any(),any());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }
    @Test void 请求校验拒绝缺少坐标系和非法半径() throws Exception {
        for(String invalid:List.of("{\"latitude\":33,\"longitude\":113}",
                body.replace("\"WGS84\"","\"WGS84\",\"radius\":0"),
                body.replace("33.735","91"))){
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test void 非法页码被拒绝而不是悄悄查第一页() throws Exception {
        for(int page:List.of(0,-1,26)) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("\"WGS84\"","\"WGS84\",\"page\":"+page)))
                .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    @Test void 错误码透传但不返回上游原始响应() throws Exception {
        when(service.search(any(),any())).thenThrow(new AmapRequestException("AMAP_QUOTA_EXCEEDED"));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("AMAP_QUOTA_EXCEEDED"));
    }

    @Test void 未启用服务返回可理解的配置错误() throws Exception {
        when(service.search(any(),any())).thenThrow(new AmapRequestException("AMAP_NOT_CONFIGURED"));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("AMAP_NOT_CONFIGURED"));
        verify(service).search(any(),any());
    }

    @Test void 缺失可选数据仍保留JSON空字段() throws Exception {
        var restaurant=new Restaurant("B_TEST","测试餐饮","餐饮服务","050000",null,
            33.735,113.3077,100,null,null,null,null,null,null);
        when(service.search(any(),any())).thenReturn(new RestaurantSearchResponse(UUID.randomUUID(),Instant.now(),
            "SUCCESS",3000,"GCJ02","STRAIGHT_LINE",List.of(restaurant)));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.restaurants[0].poiId").value("B_TEST"))
            .andExpect(jsonPath("$.restaurants[0].averageCost").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.restaurants[0].openingHoursText").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.restaurants[0].imageUrl").value(org.hamcrest.Matchers.nullValue()));
    }
}
