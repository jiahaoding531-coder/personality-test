package com.example.personality.amap;

import com.example.personality.config.AmapProperties;
import com.example.personality.dto.LocationSearchRequest;
import com.example.personality.exception.AmapRequestException;
import com.example.personality.service.AMapLocationSearchService;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.hamcrest.Matchers.startsWith;

/** HTTP 传输替身验证真实高德客户端/JSON解析，不读取环境密钥、不依赖本机 socket。 */
@Timeout(10)
class AMapLocationHttpTest {
    MockRestServiceServer server;
    AMapLocationSearchService search;
    String response="{\"status\":\"1\",\"pois\":[]}";
    String requestedPath;
    @BeforeEach void setup() {
        var properties=new AmapProperties();
        properties.setKey("test-not-a-real-key");
        var client=new AmapClient(properties);
        var builder=RestClient.builder().baseUrl("https://amap.test");
        server=MockRestServiceServer.bindTo(builder).build();
        server.expect(manyTimes(),requestTo(startsWith("https://amap.test/v5/place/text?")))
                .andExpect(queryParam("keywords","%E8%A5%BF%E6%B9%96")).andExpect(queryParam("page_size","10"))
                .andRespond(request->{
                    requestedPath=request.getURI().getPath();
                    return withSuccess(response,MediaType.APPLICATION_JSON).createResponse(request);
                });
        ReflectionTestUtils.setField(client,"restClient",builder.build());
        search=new AMapLocationSearchService(client);
    }
    @Test void 实际HTTP解析真实候选结构且只调用关键词搜索() {
        response="""
            {"status":"1","info":"OK","infocode":"10000","pois":[
              {"id":"public-test","name":"西湖","pname":"浙江省","cityname":"杭州市","adname":"西湖区","location":"120.14,30.24"}]}
            """;
        var result=search.search(new LocationSearchRequest("西湖"));
        assertEquals("/v5/place/text",requestedPath);
        assertEquals("西湖",result.candidates().get(0).name());
        assertEquals("GCJ02",result.candidates().get(0).coordinateSystem());
    }
    @Test void 真实高德错误结构保留权限额度分类且不公开原响应() {
        for(String[] item:new String[][]{{"10001","AMAP_PERMISSION_DENIED"},{"10003","AMAP_QUOTA_EXCEEDED"}}) {
            response="{\"status\":\"0\",\"infocode\":\""+item[0]+"\",\"info\":\"上游私有信息\"}";
            var error=assertThrows(AmapRequestException.class,()->search.search(new LocationSearchRequest("西湖")));
            assertEquals(item[1],error.code());
            assertFalse(error.getMessage().contains("上游私有信息"));
        }
    }
    @Test void 网络断开及坏JSON不是空候选() {
        response="not-json";
        assertEquals("AMAP_SEARCH_FAILED",assertThrows(AmapRequestException.class,()->search.search(new LocationSearchRequest("西湖"))).code());
        server.reset();
        server.expect(requestTo(startsWith("https://amap.test/v5/place/text?")))
                .andRespond(request->{throw new java.net.SocketTimeoutException("只供测试的网络故障");});
        assertEquals("AMAP_NETWORK_ERROR",assertThrows(AmapRequestException.class,()->search.search(new LocationSearchRequest("西湖"))).code());
    }
}
