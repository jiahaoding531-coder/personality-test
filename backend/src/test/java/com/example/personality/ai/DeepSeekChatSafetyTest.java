package com.example.personality.ai;

import com.example.personality.config.*;
import com.example.personality.exception.*;
import com.example.personality.security.AppUserPrincipal;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import java.time.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用本机 HTTP 替身，绝不连接任何真实模型。 */
class DeepSeekChatSafetyTest {
    final AtomicInteger requests=new AtomicInteger();
    final AtomicReference<String> sent=new AtomicReference<>();
    String response="{\"choices\":[{\"message\":{\"content\":\"测试回答\"},\"finish_reason\":\"stop\"}]}";
    int status=200;
    DeepSeekChatClient client;
    AiCredentials credentials;
    @BeforeEach void setup() throws Exception {
        credentials=AiCredentials.forServer("http://127.0.0.1/test","test","test-private-key-not-real");
        var limits=new AiSafetyProperties(6,2,100,300,200000,1000000,20000,1024);
        client=new DeepSeekChatClient(new AiProperties(),new AiCallGuard(limits,Clock.systemUTC(),mock(AiBudgetStore.class)));
        var builder=RestClient.builder();
        var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://127.0.0.1/test/chat/completions")).andRespond(request -> {
            requests.incrementAndGet(); sent.set(((MockClientHttpRequest)request).getBodyAsString());
            return withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body(response).createResponse(request);
        });
        ReflectionTestUtils.setField(client,"restClient",builder.build());
    }
    @AfterEach void close(){ SecurityContextHolder.clearContext(); }
    void login() { var principal=new AppUserPrincipal(7L,"不应发送的用户名","");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities())); }
    @Test void 未认证请求不能发往模型() {
        assertThrows(AiRequestRejectedException.class,()->client.complete(credentials,"规则","需求"));
        assertEquals(0,requests.get());
    }
    @Test void 原话个人联系方式不进入模型Prompt() {
        login(); assertEquals("测试回答",client.complete(credentials,"规则","联系我13800138000，邮箱demo@example.com，想安静一点"));
        assertFalse(sent.get().contains("13800138000")); assertFalse(sent.get().contains("demo@example.com"));
        assertFalse(sent.get().contains("不应发送的用户名")); assertTrue(sent.get().contains("想安静一点"));
    }
    @Test void 非JSON上游响应作为可降级AI故障() {
        login(); response="not-json";
        assertThrows(AiServiceException.class,()->client.complete(credentials,"规则","需求"));
    }
    @Test void 上游拒绝不能向调用方泄漏Key或原始响应() {
        login(); status=401; response="{\"message\":\"test-private-key-not-real private-data\"}";
        var ex=assertThrows(AiServiceException.class,()->client.complete(credentials,"规则","需求"));
        assertFalse(ex.toString().contains("test-private-key")); assertNull(ex.getCause());
    }
    @Test void 超时释放在途名额且不保留底层异常原文() {
        login();
        var builder=RestClient.builder();
        var server=MockRestServiceServer.bindTo(builder).build();
        var attempts=new AtomicInteger();
        server.expect(org.springframework.test.web.client.ExpectedCount.twice(),
                requestTo("http://127.0.0.1/test/chat/completions")).andRespond(request -> {
            if(attempts.incrementAndGet()==1) throw new java.net.SocketTimeoutException("private-upstream-data");
            return withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(response).createResponse(request);
        });
        ReflectionTestUtils.setField(client,"restClient",builder.build());
        var ex=assertThrows(AiServiceException.class,()->client.complete(credentials,"规则","需求"));
        assertNull(ex.getCause());
        assertFalse(ex.toString().contains("private-upstream-data"));
        assertEquals("测试回答",client.complete(credentials,"规则","需求"));
        server.verify();
    }
}
