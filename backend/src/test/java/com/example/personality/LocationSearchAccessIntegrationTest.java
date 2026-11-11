package com.example.personality;

import com.example.personality.dto.LocationSearchResponse;
import com.example.personality.service.AMapLocationSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 待本地 PostgreSQL 恢复后执行：真实 Security、会话和账号归属，不调用高德。 */
class LocationSearchAccessIntegrationTest extends IntegrationTestBase {
    @MockitoBean AMapLocationSearchService search;
    final String body="{\"keyword\":\"杭州西湖\"}";
    @Test void AI关闭时合法令牌仍可搜索而缺失CSRF被拒绝() throws Exception {
        var ref=createTravelSession(null);
        when(search.search(any())).thenReturn(new LocationSearchResponse(List.of()));
        var path="/api/travel/sessions/"+ref.id()+"/locations/search";
        mockMvc.perform(withToken(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body),ref))
                .andExpect(status().isOk()).andExpect(jsonPath("$.candidates").isEmpty());
        clearInvocations(search);
        mockMvc.perform(withToken(post(path).contentType(MediaType.APPLICATION_JSON).content(body),ref))
                .andExpect(status().isForbidden());
        verifyNoInteractions(search);
    }
    @Test void 错误会话令牌及其他账号携带旧令牌都被拒绝() throws Exception {
        var owner=registerAndLogin(uniqueUsername("loc_owner"),"test-password");
        var other=registerAndLogin(uniqueUsername("loc_other"),"test-password");
        var ref=createTravelSession(owner);
        var path="/api/travel/sessions/"+ref.id()+"/locations/search";
        for(String token:List.of("", "bad-token",UUID.randomUUID().toString()))
            mockMvc.perform(post(path).with(csrf()).header("X-Session-Token",token)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        mockMvc.perform(withToken(post(path).session(other).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body),ref))
                .andExpect(status().isNotFound());
        verifyNoInteractions(search);
    }
}
