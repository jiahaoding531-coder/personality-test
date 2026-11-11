package com.example.personality.ai;

import org.junit.jupiter.api.Test;
import java.time.LocalTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AiExplanationSafetyTest {
    @Test void 未核验的实时事实不能进入推荐解释() {
        var client=org.mockito.Mockito.mock(DeepSeekChatClient.class);
        var generator=new DeepSeekTravelReasonGenerator(client,new TravelReasonPromptBuilder());
        var input=new TravelReasonInput(null,null,240,10,null,Set.of(),Set.of(),Map.of(),null,List.of(place()));
        for (var text:List.of("现在正在营业，无需排队", "人均只需20元", "距离只有200米", "全天开放")) {
            org.mockito.Mockito.when(client.complete(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(true)))
                    .thenReturn("{\"reasons\":[{\"rank\":1,\"reason\":\""+text+"\"}]}");
            var result=generator.generateReasons(AiCredentials.forServer("https://example.invalid","test","fake"),input);
            assertFalse(result.get(0).reason().contains(text));
            assertFalse(result.get(0).modelSelected());
        }
    }
    @Test void 凭据对象意外转成字符串也不能泄漏密钥() {
        assertFalse(AiCredentials.forServer("https://example.invalid", "test", "secret-fixture").toString()
                .contains("secret-fixture"));
    }
    @Test void 解析失败不得把模型原文写进日志或异常链() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(DeepSeekTravelReasonGenerator.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var error = assertThrows(com.example.personality.exception.AiServiceException.class,
                    () -> DeepSeekTravelReasonGenerator.parseSelections("PRIVATE_MODEL_FIXTURE", TravelReasonEvidence.from(TravelReasonTrustRegressionTest.input())));
            assertNull(error.getCause());
            assertTrue(appender.list.stream().noneMatch(e -> e.getFormattedMessage().contains("PRIVATE_MODEL_FIXTURE")));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
    @Test void 重复和越界名次不能生成多余解释() {
        assertThrows(com.example.personality.exception.AiServiceException.class,()->DeepSeekTravelReasonGenerator.parseSelections("""
                {"reasons":[{"rank":1,"reason":"符合自然偏好"},
                {"rank":1,"reason":"重复"},{"rank":4,"reason":"不存在的地点"},
                {"rank":2.5,"reason":"非法名次"}]}
                """, TravelReasonEvidence.from(TravelReasonTrustRegressionTest.input())));
    }
    @Test void 非对象与拼接JSON应作为格式错误处理() {
        for (var content : List.of("null", "[]", "{\"reasons\":[{\"rank\":1,\"reason\":\"解释\"}]} {}")) {
            assertThrows(com.example.personality.exception.AiServiceException.class,
                    () -> DeepSeekTravelReasonGenerator.parseSelections(content, TravelReasonEvidence.from(TravelReasonTrustRegressionTest.input())));
        }
    }
    @Test void 平顶山解释不能携带杭州角色或精确街道() {
        var builder=new TravelReasonPromptBuilder();
        var input=new TravelReasonInput("平顶山市新华区某某街123号",LocalTime.NOON,240,10,null,
                Set.of(),Set.of(),Map.of(),null,List.of(place()));
        assertFalse(builder.systemPrompt().contains("懂杭州"));
        var prompt=builder.userPrompt(input);
        assertFalse(prompt.contains("平顶山市新华区"));
        assertFalse(prompt.contains("某某街123号"));
        assertTrue(prompt.contains("evidence"));
    }
    @Test void 未知营业时间不写全天开放() {
        var input=new TravelReasonInput(null,null,240,10,null,Set.of(),Set.of(),Map.of(),null,List.of(place()));
        var prompt=new TravelReasonPromptBuilder().userPrompt(input);
        assertFalse(prompt.contains("全天开放"));
        assertTrue(prompt.contains("实时营业状态"));
    }
    private TravelReasonInput.ExplainedPlace place() {
        return new TravelReasonInput.ExplainedPlace(1,"测试公园","NATURE","真实介绍",80,
                new TravelReasonInput.Factors(0.8,1,1,1,1),0,60,null,null,List.of());
    }
}
