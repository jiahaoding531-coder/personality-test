package com.example.personality;

import com.example.personality.dto.RestaurantSearchResponse;
import com.example.personality.service.AMapRestaurantSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实安全过滤器和 PostgreSQL 会话校验；仅替换收费的外部搜索。 */
class RestaurantAccessIntegrationTest extends IntegrationTestBase {
    @MockitoBean AMapRestaurantSearchService service;
    private static final String BODY="{\"latitude\":33.735,\"longitude\":113.3077,\"coordinateSystem\":\"WGS84\"}";

    @Test void 合法匿名令牌可以搜索() throws Exception {
        var ref=createTravelSession(null);
        when(service.search(any())).thenReturn(new RestaurantSearchResponse(UUID.randomUUID(),Instant.now(),
            "NO_RESULTS",3000,"GCJ02","STRAIGHT_LINE",List.of()));
        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/restaurants/search",ref.id())
            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY),ref))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NO_RESULTS"));
    }

    @Test void 缺失或错误令牌不可调用搜索() throws Exception {
        var ref=createTravelSession(null);
        for(String token:List.of("", "bad-token", UUID.randomUUID().toString())) {
            mockMvc.perform(post("/api/travel/sessions/{id}/restaurants/search",ref.id())
                .with(csrf()).header("X-Session-Token",token).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());
        }
        verifyNoInteractions(service);
    }

    @Test void 缺失CSRF不能调用搜索() throws Exception {
        var ref=createTravelSession(null);
        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/restaurants/search",ref.id())
            .contentType(MediaType.APPLICATION_JSON).content(BODY),ref)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
