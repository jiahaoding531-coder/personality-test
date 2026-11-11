package com.example.personality.ai;

import com.example.personality.config.AiProperties;
import com.example.personality.config.AiSafetyProperties;
import com.example.personality.dto.InterpretResponse;
import com.example.personality.exception.AiServiceException;
import com.example.personality.security.AppUserPrincipal;
import com.example.personality.service.TravelIntentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/** 替换最外层 HTTP 传输，真实执行客户端、预算闸门、需求解析与解释校验；不联网。 */
class DeepSeekCompatibilityTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @AfterEach
    void 清除测试身份() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 服务端默认凭据发出Flash非思考JSON请求且不扩大Token上限() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.setApiKey("compatibility-test-key-not-real");
        AiCredentials credentials = new AiCredentialsResolver(properties).require(null, null);
        Fixture fixture = fixture(credentials, completion("{}", "stop"), 200);

        assertEquals("{}", fixture.client.complete(credentials, "只返回 json 对象", "测试需求", true));
        JsonNode body = fixture.body();
        assertEquals("deepseek-flash", body.path("model").asString());
        assertEquals("disabled", body.path("thinking").path("type").asString());
        assertEquals("json_object", body.path("response_format").path("type").asString());
        assertEquals(1200, body.path("max_tokens").asInt());
        assertFalse(body.path("stream").asBoolean());
        fixture.singleRequest();
    }

    @Test
    void DeepSeek自带凭据使用Flash且普通文本请求也关闭思考() {
        AiCredentials credentials = AiCredentials.forUser(AiProvider.DEEPSEEK, "fixture-key-not-real");
        Fixture fixture = fixture(credentials, completion("测试回答", "stop"), 200);

        assertEquals("测试回答", fixture.client.complete(credentials, "规则", "需求"));
        assertEquals("deepseek-flash", fixture.body().path("model").asString());
        assertEquals("disabled", fixture.body().path("thinking").path("type").asString());
        assertTrue(fixture.body().path("response_format").isMissingNode());
        fixture.singleRequest();
    }

    @Test
    void 服务端官方端点没有Provider枚举也必须关闭思考() {
        AiCredentials credentials = officialCredentials();
        Fixture fixture = fixture(credentials, completion("{}", "stop"), 200);
        fixture.client.complete(credentials, "json 规则", "需求", true);
        assertEquals("disabled", fixture.body().path("thinking").path("type").asString());
        fixture.singleRequest();
    }

    @Test
    void 其他厂商和相似域名不接收DeepSeek专属参数() {
        for (AiCredentials credentials : List.of(
                AiCredentials.forUser(AiProvider.DASHSCOPE, "fixture-key-not-real"),
                AiCredentials.forUser(AiProvider.ZHIPU, "fixture-key-not-real"),
                AiCredentials.forUser(AiProvider.MOONSHOT, "fixture-key-not-real"),
                AiCredentials.forUser(AiProvider.VOLCENGINE, "fixture-key-not-real"),
                AiCredentials.forServer("https://api.deepseek.com.example.invalid/v1", "deepseek-flash", "fixture"))) {
            Fixture fixture = fixture(credentials, completion("测试回答", "stop"), 200);
            assertEquals("测试回答", fixture.client.complete(credentials, "规则", "需求"));
            assertTrue(fixture.body().path("thinking").isMissingNode());
            fixture.singleRequest();
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"length", "content_filter", "tool_calls", "insufficient_system_resource", "aborted", "unknown"})
    void 非正常结束即使正文是完整JSON也不能执行且不自动重试(String finishReason) {
        AiCredentials credentials = officialCredentials();
        Fixture fixture = fixture(credentials, completion("{\"operations\":[{\"op\":\"CLEAR_TRAVEL_INTENT\"}]}", finishReason), 200);
        AiServiceException error = assertThrows(AiServiceException.class,
                () -> fixture.client.complete(credentials, "json 规则", "需求", true));
        assertNull(error.getCause());
        assertFalse(error.toString().contains("CLEAR_TRAVEL_INTENT"));
        fixture.singleRequest();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "null", "not-json", "{\"choices\":null}", "{\"choices\":[]}", "{\"choices\":[null]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":null}]}",
            "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}"})
    void 空响应畸形外层JSON或缺少结束标志均安全降级(String response) {
        AiCredentials credentials = officialCredentials();
        Fixture fixture = fixture(credentials, response, 200);
        assertThrows(AiServiceException.class, () -> fixture.client.complete(credentials, "json 规则", "需求", true));
        fixture.singleRequest();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void 只有推理内容但没有最终正文时不能当作业务结果(String content) {
        AiCredentials credentials = officialCredentials();
        Fixture fixture = fixture(credentials, completion(content, "stop"), 200);
        assertThrows(AiServiceException.class, () -> fixture.client.complete(credentials, "json 规则", "需求", true));
        fixture.singleRequest();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429, 500, 503})
    void 上游错误仅调用一次且不透传原文或密钥(int status) {
        AiCredentials credentials = officialCredentials();
        Fixture fixture = fixture(credentials, "{\"error\":\"fixture-private-key PRIVATE_UPSTREAM_BODY\"}", status);
        AiServiceException error = assertThrows(AiServiceException.class,
                () -> fixture.client.complete(credentials, "json 规则", "需求", true));
        assertNull(error.getCause());
        assertFalse(error.toString().contains("PRIVATE_UPSTREAM_BODY"));
        assertFalse(error.toString().contains("fixture-private-key"));
        fixture.singleRequest();
    }

    @Test
    void Flash响应经过真实需求服务仍过滤非法Operations() {
        String content = """
                {"operations":[{"op":"ADD_PREFERENCE","value":"QUIET"},
                {"op":"SET_CONSTRAINT","key":"durationMinutes","value":90},
                {"op":"SET_CONSTRAINT","key":"budgetMax","value":5000},
                {"op":"CLEAR_TRAVEL_INTENT","userId":999},
                {"op":"INVENT_PLACE","value":"假地点"}],"summary":"安静一些，剩余一段时间"}
                """;
        AiCredentials credentials = officialCredentials();
        Fixture fixture = fixture(credentials, completion(content, "stop"), 200);
        TravelIntentService service = new TravelIntentService(
                new DeepSeekTextIntentGenerator(fixture.client, new TextIntentPromptBuilder()));
        InterpretResponse result = service.interpret(credentials, "安静一些，只有一个半小时");
        assertEquals(2, result.operations().size());
        assertEquals("ADD_PREFERENCE", result.operations().get(0).op());
        assertEquals("SET_CONSTRAINT", result.operations().get(1).op());
        assertEquals("json_object", fixture.body().path("response_format").path("type").asString());
        fixture.singleRequest();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{broken", "[]", "{} {}"})
    void 外层成功但模型正文不是合法业务JSON仍拒绝(String content) {
        AiCredentials credentials = officialCredentials();
        Fixture fixture = fixture(credentials, completion(content, "stop"), 200);
        TravelIntentService service = new TravelIntentService(
                new DeepSeekTextIntentGenerator(fixture.client, new TextIntentPromptBuilder()));
        assertThrows(AiServiceException.class, () -> service.interpret(credentials, "测试需求"));
        fixture.singleRequest();
    }

    @Test
    void Flash真实解析链可以生成有依据的推荐解释() {
        AiCredentials credentials = officialCredentials();
        var place = new TravelReasonInput.ExplainedPlace(1, "公开测试公园", "NATURE", "测试介绍", 80,
                new TravelReasonInput.Factors(0.8, 1, 1, 1, 1), 0, 60, null, null, List.of());
        var input = new TravelReasonInput(null, null, 240, 10, null,
                Set.of(), Set.of(), Map.of(), null, List.of(place));
        var catalog=TravelReasonEvidence.from(input);
        String id=catalog.forRank(1).get(0).id();
        Fixture fixture = fixture(credentials, completion(
                "{\"selections\":[{\"rank\":1,\"evidenceIds\":[\""+id+"\"]}]}", "stop"), 200);
        var generator = new DeepSeekTravelReasonGenerator(fixture.client, new TravelReasonPromptBuilder());
        var reasons = generator.generateReasons(credentials, input);
        assertEquals(1, reasons.size());
        assertEquals(1, reasons.get(0).rank());
        assertEquals(catalog.render(1,List.of(id)), reasons.get(0).reason());
        assertTrue(reasons.get(0).modelSelected());
        assertEquals("json_object", fixture.body().path("response_format").path("type").asString());
        fixture.singleRequest();
    }

    private static AiCredentials officialCredentials() {
        return AiCredentials.forServer("https://api.deepseek.com/v1", "deepseek-flash", "fixture-private-key");
    }

    @Test
    void 合法依据ID不能被既有隐私清洗改写() {
        var phoneShape=java.util.regex.Pattern.compile("(?<![0-9])1[3-9][0-9]{9}(?![0-9])");
        TravelReasonInput chosen=null;
        for(int i=0;i<10000;i++) {
            var candidate=TravelReasonEvidenceTest.input("privacy-scope:"+i,Map.of(),Set.of());
            if(phoneShape.matcher(TravelReasonEvidence.from(candidate).fingerprint()).find()) {chosen=candidate;break;}
        }
        assertNotNull(chosen,"必须找到确定性的数字形态指纹测试样本");
        var catalog=TravelReasonEvidence.from(chosen);
        String id=catalog.forRank(1).get(0).id();
        Fixture fixture=fixture(officialCredentials(),completion(
                "{\"selections\":[{\"rank\":1,\"evidenceIds\":[\""+id+"\"]}]}","stop"),200);
        var result=new DeepSeekTravelReasonGenerator(fixture.client,new TravelReasonPromptBuilder())
                .generateReasons(officialCredentials(),chosen);
        assertTrue(result.get(0).modelSelected(),"真实客户端清洗不能让合法选择被降级");
        assertTrue(fixture.body().path("messages").toString().contains(id));
        fixture.singleRequest();
    }

    private static Fixture fixture(AiCredentials credentials, String response, int status) {
        var principal = new AppUserPrincipal(71L, "合成测试用户", "");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        var limits = new AiSafetyProperties(6, 2, 100, 300, 200000, 1000000, 20000, 1024);
        var client = new DeepSeekChatClient(new AiProperties(),
                new AiCallGuard(limits, Clock.systemUTC(), mock(AiBudgetStore.class)));
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var requests = new AtomicInteger();
        var sent = new AtomicReference<String>();
        server.expect(requestTo(credentials.baseUrl() + "/chat/completions")).andRespond(request -> {
            requests.incrementAndGet();
            sent.set(((MockClientHttpRequest) request).getBodyAsString());
            return withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
                    .body(response).createResponse(request);
        });
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        return new Fixture(client, server, requests, sent);
    }

    private static String completion(String content, String finishReason) {
        return """
                {"id":"compat-fixture","object":"chat.completion","created":1792368000,
                "model":"deepseek-flash","choices":[{"index":0,
                "message":{"role":"assistant","content":%s,"reasoning_content":"PRIVATE_REASONING_FIXTURE"},
                "finish_reason":%s}],"usage":{"prompt_tokens":20,"completion_tokens":30,"total_tokens":50}}
                """.formatted(JSON.writeValueAsString(content), JSON.writeValueAsString(finishReason));
    }

    private record Fixture(DeepSeekChatClient client, MockRestServiceServer server,
                           AtomicInteger requests, AtomicReference<String> sent) {
        JsonNode body() { return JSON.readTree(sent.get()); }
        void singleRequest() { assertEquals(1, requests.get()); server.verify(); }
    }
}
