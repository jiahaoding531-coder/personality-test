package com.example.personality.service;

import com.example.personality.amap.*;
import com.example.personality.domain.*;
import com.example.personality.dto.RestaurantSearchRequest;
import com.example.personality.exception.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AMapRestaurantSearchServiceTest {
    final AmapClient client = mock(AmapClient.class);
    final AMapRestaurantSearchService service = new AMapRestaurantSearchService(client, new AmapCoordinateConverter(client));
    final JsonMapper json = JsonMapper.builder().build();

    @Test void 第二页传给高德且响应标记实际页码() {
        when(client.getRequired(eq("/v5/place/around"),anyMap())).thenReturn(json.readTree("{\"pois\":[]}"));
        var request=json.readValue("{\"latitude\":33.735,\"longitude\":113.3077,\"coordinateSystem\":\"GCJ02\",\"page\":2}",RestaurantSearchRequest.class);
        var result=service.search(request);
        verify(client).getRequired(eq("/v5/place/around"),argThat(p->"2".equals(p.get("page_num"))));
        assertEquals(2,json.valueToTree(result).path("currentPage").asInt());
    }

    @Test void 不突破距离边界且去重按距离返回() {
        when(client.getRequired(eq("/v5/place/around"),anyMap())).thenReturn(json.readTree("""
            {"pois":[
              {"id":"far","name":"边界外测试","location":"113.31,33.73","distance":"1000.9"},
              {"id":"edge","name":"边界测试","location":"113.31,33.73","distance":"1000"},
              {"id":"near","name":"近处测试","location":"113.31,33.73","distance":"200"},
              {"id":"near","name":"重复测试","location":"113.31,33.73","distance":"200"}
            ]}
            """));
        var result=service.search(new RestaurantSearchRequest(33.735,113.3077,CoordinateSystem.GCJ02,1000));
        assertEquals(List.of("near","edge"),result.restaurants().stream().map(r->r.poiId()).toList());
        assertEquals("SUCCESS",result.status());
        assertNull(result.restaurants().get(0).averageCost());
    }

    @Test void 手机坐标必须转换后搜索() {
        when(client.getRequired(eq("/v3/assistant/coordinate/convert"), anyMap()))
            .thenReturn(json.readTree("{\"locations\":\"113.313,33.733\"}"));
        when(client.getRequired(eq("/v5/place/around"), anyMap()))
            .thenReturn(json.readTree("{\"pois\":[]}"));
        var response = service.search(new RestaurantSearchRequest(33.735,113.3077,CoordinateSystem.WGS84,null));
        assertTrue(response.restaurants().isEmpty());
        assertEquals("NO_RESULTS", response.status());
        assertEquals("STRAIGHT_LINE", response.distanceType());
        assertNotNull(response.searchSessionId());
        assertNotNull(response.timestamp());
        verify(client).getRequired("/v5/place/around", Map.of("location","113.313000,33.733000",
            "types","050000","sortrule","distance","radius","3000","page_size","10","page_num","1","show_fields","business,photos"));
    }

    @Test void 高德坐标不重复转换且使用用户半径() {
        when(client.getRequired(eq("/v5/place/around"), anyMap())).thenReturn(json.readTree("{\"pois\":[]}"));
        assertEquals(1000, service.search(new RestaurantSearchRequest(33.735,113.3077,CoordinateSystem.GCJ02,1000)).radius());
        verify(client, never()).getRequired(eq("/v3/assistant/coordinate/convert"),anyMap());
    }

    @Test void 转换失败不搜索() {
        when(client.getRequired(eq("/v3/assistant/coordinate/convert"),anyMap()))
            .thenReturn(json.readTree("{\"locations\":\"bad\"}"));
        assertThrows(CoordinateConversionException.class,
            () -> service.search(new RestaurantSearchRequest(33.735,113.3077,CoordinateSystem.WGS84,3000)));
        verify(client, never()).getRequired(eq("/v5/place/around"),anyMap());
    }

    @Test void 转换权限错误保留分类且不搜索() {
        when(client.getRequired(eq("/v3/assistant/coordinate/convert"),anyMap()))
            .thenThrow(new AmapRequestException("AMAP_PERMISSION_DENIED"));
        var error=assertThrows(AmapRequestException.class,
            () -> service.search(new RestaurantSearchRequest(33.735,113.3077,CoordinateSystem.WGS84,3000)));
        assertEquals("AMAP_PERMISSION_DENIED",error.code());
        verify(client, never()).getRequired(eq("/v5/place/around"),anyMap());
    }

    @Test void 未确认来源和非法半径在网络请求前拒绝() {
        assertThrows(CoordinateConversionException.class,
            () -> service.search(new RestaurantSearchRequest(33.735,113.3077,CoordinateSystem.UNKNOWN,3000)));
        assertThrows(IllegalArgumentException.class,
            () -> service.search(new RestaurantSearchRequest(33.735,113.3077,CoordinateSystem.GCJ02,0)));
        verifyNoInteractions(client);
    }
}
