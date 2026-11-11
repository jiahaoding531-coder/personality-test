package com.example.personality.service;

import com.example.personality.amap.AmapClient;
import com.example.personality.dto.LocationSearchRequest;
import com.example.personality.exception.AmapRequestException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AMapLocationSearchServiceTest {
    final AmapClient client=mock(AmapClient.class);
    final JsonMapper json=JsonMapper.builder().build();
    final AMapLocationSearchService service=new AMapLocationSearchService(client);

    @Test void 关键词搜索保留重名行政区与原始精度并排除无效坐标() {
        when(client.getRequired(eq("/v5/place/text"),anyMap())).thenReturn(json.readTree("""
            {"pois":[
              {"id":"A","name":"人民公园","address":"公园路1号","pname":"河南省","cityname":"平顶山市","adname":"新华区","location":"113.30771234,33.73512345"},
              {"id":"B","name":"人民公园","address":[],"pname":"河南省","cityname":"平顶山市","adname":"卫东区","location":"113.32,33.74"},
              {"id":"bad","name":"没有坐标","location":[]},
              {"id":"bad2","name":"越界坐标","location":"181,33"},
              {"id":"bad3","name":"非法坐标","location":"NaN,33"},
              {"id":"bad4","name":"多余坐标","location":"113,33,10"}
            ]}
            """));
        var result=service.search(new LocationSearchRequest(" 人民公园 "));
        assertEquals(2,result.candidates().size());
        var first=result.candidates().get(0);
        assertEquals("新华区",first.district());
        assertEquals("卫东区",result.candidates().get(1).district());
        assertNull(result.candidates().get(1).address());
        assertEquals(33.73512345,first.latitude());
        assertEquals(113.30771234,first.longitude());
        assertEquals("GCJ02",first.coordinateSystem());
        assertEquals("AMAP_POI",first.source());
        assertEquals("A",first.poiId());
        verify(client).getRequired("/v5/place/text",Map.of("keywords","人民公园","page_size","10","page_num","1"));
        verifyNoMoreInteractions(client);
    }
    @Test void 真正无结果返回空候选而异常响应不能冒充无结果() {
        when(client.getRequired(anyString(),anyMap())).thenReturn(json.readTree("{\"pois\":[]}"));
        assertTrue(service.search(new LocationSearchRequest("不存在的地名")).candidates().isEmpty());
        when(client.getRequired(anyString(),anyMap())).thenReturn(json.readTree("{}"));
        assertThrows(AmapRequestException.class,()->service.search(new LocationSearchRequest("西湖")));
    }
    @Test void 权限额度与网络错误不会伪造候选() {
        for(String code:new String[]{"AMAP_PERMISSION_DENIED","AMAP_QUOTA_EXCEEDED","AMAP_NETWORK_ERROR"}) {
            doThrow(new AmapRequestException(code)).when(client).getRequired(anyString(),anyMap());
            assertEquals(code,assertThrows(AmapRequestException.class,()->service.search(new LocationSearchRequest("西湖"))).code());
        }
    }
    @Test void 非法关键词不请求高德() {
        for(String word:new String[]{"", "  ", "字".repeat(81)})
            assertThrows(IllegalArgumentException.class,()->service.search(new LocationSearchRequest(word)));
        verifyNoInteractions(client);
    }
}
