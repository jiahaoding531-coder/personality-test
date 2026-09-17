package com.example.personality;

import com.example.personality.ai.AiCredentialsResolver;
import com.example.personality.ai.TextIntentGenerator;
import com.example.personality.ai.TextIntentResult;
import com.example.personality.ai.TravelIntentOperation;
import com.example.personality.domain.TravelDimension;
import com.example.personality.domain.TravelState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「把一句话翻译成结构化条件」的端到端测试。
 *
 * <p>用假的 {@link TextIntentGenerator} 换掉真实现——真调一次大模型要几秒、要花钱、
 * 而且输出不可复现，没法断言"它应该解析成什么"。
 *
 * <p>解析的容错逻辑（认不出的状态名、越界的数值）由
 * {@code DeepSeekTextIntentGeneratorTest} 覆盖，两边合起来，
 * 从"模型返回什么"到"接口给出什么"整条链路都有测试。
 */
@SpringBootTest(properties = {
        "app.ai.enabled=true",
        "app.ai.api-key=server-test-key",
        "app.ai.allow-user-keys=true"
})
class TravelIntentIntegrationTest extends IntegrationTestBase {

    @BeforeEach
    void resetFake() {
        FakeIntentGenerator.calls.set(0);
        FakeIntentGenerator.lastCredentials = null;
        FakeIntentGenerator.next = new TextIntentResult(
                Set.of(TravelState.TIRED, TravelState.QUIET),
                Map.of(TravelDimension.CROWD_TOLERANCE, -0.5),
                60, 2.0, null,
                List.of("想找个能带狗的地方"),
                "你有点累了，想找个安静的地方待一会儿",
                List.of(
                        new TravelIntentOperation(
                                TravelIntentOperation.Type.ADD_PREFERENCE,
                                TravelState.TIRED, List.of(), null, null, Map.of()),
                        new TravelIntentOperation(
                                TravelIntentOperation.Type.ADD_PREFERENCE,
                                TravelState.QUIET, List.of(), null, null, Map.of()),
                        new TravelIntentOperation(
                                TravelIntentOperation.Type.SET_CONSTRAINT,
                                null, List.of(),
                                TravelIntentOperation.ConstraintKey.DURATION_MINUTES,
                                60.0, Map.of())
                ));
    }

    @Test
    @DisplayName("【核心】一句话 → 结构化的状态、倾向、数值，全都带中文名")
    void interpretsTextIntoStructuredConditions() throws Exception {
        TestSessionRef ref = createTravelSession(null);

        mockMvc.perform(interpretRequest(ref, "我有点累了，想找个安静的地方坐坐，还有一个小时"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usable").value(true))
                // 状态带中文名——前端不用自己维护一份翻译表
                .andExpect(jsonPath("$.states.length()").value(2))
                .andExpect(jsonPath("$.states[0].label").isString())
                // 倾向用维度名当键，前端要原样回传在推荐请求的 biases 里
                .andExpect(jsonPath("$.biases.CROWD_TOLERANCE").value(-0.5))
                .andExpect(jsonPath("$.remainingMinutes").value(60))
                .andExpect(jsonPath("$.maxDistanceKm").value(2.0))
                .andExpect(jsonPath("$.operations.length()").value(3))
                .andExpect(jsonPath("$.operations[0].op").value("ADD_PREFERENCE"))
                .andExpect(jsonPath("$.operations[0].value").value("TIRED"))
                .andExpect(jsonPath("$.operations[2].key").value("durationMinutes"))
                .andExpect(jsonPath("$.operations[2].value").value(60.0))
                // ⚠️ 用户没提预算，就必须是 null。编一个出来比留空有害得多——
                //    用户会看到一个自己从没要求过的条件被悄悄施加了
                .andExpect(jsonPath("$.maxTicketPrice").doesNotExist())
                .andExpect(jsonPath("$.summary").isString());

        assertEquals(1, FakeIntentGenerator.calls.get());
    }

    @Test
    @DisplayName("⚠️ 听懂了但用不上的部分要如实带回来")
    void unrecognizedPartsAreReturnedHonestly() throws Exception {
        TestSessionRef ref = createTravelSession(null);

        // 这一条是这个功能的诚实所在：说不出来就说"这句我没用上"，
        // 比假装听懂强——用户据此才知道系统的边界在哪
        mockMvc.perform(interpretRequest(ref, "想找个能带狗的地方"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unrecognized.length()").value(1))
                .andExpect(jsonPath("$.unrecognized[0]").value("想找个能带狗的地方"));
    }

    @Test
    @DisplayName("什么都没解析出来时 usable=false，前端据此提示换个说法")
    void nothingParsedMeansNotUsable() throws Exception {
        TestSessionRef ref = createTravelSession(null);
        FakeIntentGenerator.next = TextIntentResult.empty();

        mockMvc.perform(interpretRequest(ref, "今天天气不错"))
                .andExpect(status().isOk())
                // ⚠️ 注意是 200 而不是错误码："这句我没听懂"是一个正常结果，
                //    不是故障。前端据此提示换说法，而不是报一个红色的错误。
                .andExpect(jsonPath("$.usable").value(false));
    }

    @Test
    @DisplayName("空文本被校验挡下（400），且不调用模型")
    void blankTextIsRejected() throws Exception {
        TestSessionRef ref = createTravelSession(null);

        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/interpret", ref.id())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("text", "   "))), ref))
                .andExpect(status().isBadRequest());

        assertEquals(0, FakeIntentGenerator.calls.get(), "参数都没过，不该走到调模型那一步");
    }

    @Test
    @DisplayName("超长文本被挡下——它会原样进提示词")
    void overlongTextIsRejected() throws Exception {
        TestSessionRef ref = createTravelSession(null);

        // 不限长的话，一个人可以贴几万字进来：token 账单、延迟、
        // 以及模型被大段无关文本带跑偏，都跟着来
        mockMvc.perform(withToken(post("/api/travel/sessions/{id}/interpret", ref.id())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("text", "累".repeat(500)))), ref))
                .andExpect(status().isBadRequest());

        assertEquals(0, FakeIntentGenerator.calls.get());
    }

    // ==========================================================
    // 访问控制与凭据
    // ==========================================================

    @Test
    @DisplayName("【安全】别人的会话解析不了")
    void cannotInterpretForSomeoneElsesSession() throws Exception {
        TestSessionRef victim = createTravelSession(null);
        TestSessionRef attacker = createTravelSession(null);

        mockMvc.perform(post("/api/travel/sessions/{id}/interpret", victim.id())
                        .with(csrf())
                        .header(SESSION_TOKEN_HEADER, attacker.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("text", "我累了"))))
                // 404 而不是 403：403 会泄露"这个会话存在"
                .andExpect(status().isNotFound());

        assertEquals(0, FakeIntentGenerator.calls.get(),
                "被拦下的请求绝不该走到调模型那一步——那一步是要花钱的");
    }

    @Test
    @DisplayName("【BYOK】访客带自己的 key → 用的是他的")
    void visitorKeyIsUsed() throws Exception {
        TestSessionRef ref = createTravelSession(null);

        mockMvc.perform(interpretRequest(ref, "我累了")
                        .header(AiCredentialsResolver.PROVIDER_HEADER, "MOONSHOT")
                        .header(AiCredentialsResolver.KEY_HEADER, "sk-visitor-own-key"))
                .andExpect(status().isOk());

        assertEquals("sk-visitor-own-key", FakeIntentGenerator.lastCredentials.apiKey());
        assertEquals("moonshot:moonshot-v1-8k", FakeIntentGenerator.lastCredentials.providerName());
    }

    @Test
    @DisplayName("【安全】把内网地址塞进厂商头 → 400")
    void ssrfAttemptIsRejected() throws Exception {
        TestSessionRef ref = createTravelSession(null);

        mockMvc.perform(interpretRequest(ref, "我累了")
                        .header(AiCredentialsResolver.PROVIDER_HEADER, "http://169.254.169.254/")
                        .header(AiCredentialsResolver.KEY_HEADER, "sk-whatever"))
                .andExpect(status().isBadRequest());

        assertEquals(0, FakeIntentGenerator.calls.get());
    }

    // ==========================================================
    // 辅助与替身
    // ==========================================================

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
    interpretRequest(TestSessionRef ref, String text) {
        return withToken(post("/api/travel/sessions/{id}/interpret", ref.id())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\"}"), ref);
    }

    @TestConfiguration
    static class FakeIntentConfiguration {

        @Bean
        @Primary
        TextIntentGenerator fakeIntentGenerator() {
            return new FakeIntentGenerator();
        }
    }

    /** 数调用次数、能指定返回什么的假解析器。 */
    static final class FakeIntentGenerator implements TextIntentGenerator {

        static final AtomicInteger calls = new AtomicInteger();
        static volatile TextIntentResult next = TextIntentResult.empty();
        static volatile com.example.personality.ai.AiCredentials lastCredentials;

        @Override
        public TextIntentResult interpret(com.example.personality.ai.AiCredentials credentials,
                                          String text) {
            calls.incrementAndGet();
            lastCredentials = credentials;
            return next;
        }
    }
}
