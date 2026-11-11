package com.example.personality.amap;
import com.example.personality.config.AmapProperties;
import com.example.personality.exception.AmapRequestException;
import java.net.ServerSocket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AmapRequiredClientTest {
    ServerSocket server; AmapClient client;
    volatile String body="{\"status\":\"1\",\"pois\":[]}"; volatile int status=200;
    @BeforeEach void start() throws Exception {
        // 仅测试使用阻塞式 TCP，避免 Windows JDK Selector 的 Unix socket 兼容问题。
        server=new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1",0));
        Thread responder=new Thread(() -> {
            while (!server.isClosed()) {
                try (var socket=server.accept()) {
                    socket.setSoTimeout(2000);
                    var reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                    String line;
                    while ((line=reader.readLine()) != null && !line.isEmpty()) { /* 消费请求头，不记录 URL。 */ }
                    byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                    var out=socket.getOutputStream();
                    out.write(("HTTP/1.1 "+status+" Test\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(bytes); out.flush();
                } catch (IOException e) {
                    if (!server.isClosed()) throw new java.io.UncheckedIOException(e);
                }
            }
        },"高德测试响应器");
        responder.setDaemon(true); responder.start();
        var config=new AmapProperties(); config.setKey("test-secret-not-real");
        config.setBaseUrl("http://127.0.0.1:"+server.getLocalPort());
        config.setTimeout(Duration.ofSeconds(2)); client=new AmapClient(config);
    }
    @AfterEach void stop() throws Exception {if(server!=null)server.close();}
    @Test void 成功解析(){assertTrue(client.getRequired("/v5/place/around",Map.of()).has("pois"));}
    @Test void 权限与额度必须区分() {
        for(String code:new String[]{"10001","10009","10012","10013","10041","40002"}){
            body="{\"status\":\"0\",\"infocode\":\""+code+"\",\"info\":\"secret\"}";
            assertEquals("AMAP_PERMISSION_DENIED",assertThrows(AmapRequestException.class,
                ()->client.getRequired("/v5/place/around",Map.of())).code());
        }
        for(String code:new String[]{"10003","10004","10014","10020","10021","10044","40000"}){
            body="{\"status\":\"0\",\"infocode\":\""+code+"\"}";
            assertEquals("AMAP_QUOTA_EXCEEDED",assertThrows(AmapRequestException.class,
                ()->client.getRequired("/v5/place/around",Map.of())).code());
        }
    }
    @Test void 网络错误不泄露请求地址或密钥() throws Exception {
        server.close();
        var e=assertThrows(AmapRequestException.class,()->client.getRequired("/v5/place/around",Map.of()));
        assertEquals("AMAP_NETWORK_ERROR",e.code());
        assertFalse(e.getMessage().contains("test-secret")); assertNull(e.getCause());
    }
    @Test void 非法响应和服务端故障不是空结果(){
        body="not-json";
        assertEquals("AMAP_SEARCH_FAILED",assertThrows(AmapRequestException.class,
            ()->client.getRequired("/v5/place/around",Map.of())).code());
        status=503;
        assertEquals("AMAP_SEARCH_FAILED",assertThrows(AmapRequestException.class,
            ()->client.getRequired("/v5/place/around",Map.of())).code());
    }
    @Test void 原景点增强接口仍然软失败(){
        body="{\"status\":\"0\",\"infocode\":\"10003\"}";
        assertTrue(client.get("/v3/weather/weatherInfo",Map.of()).isEmpty());
    }
}
