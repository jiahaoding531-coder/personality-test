package com.example.personality;

import com.example.personality.ai.TravelFeedbackKind;
import com.example.personality.ai.AiCredentials;
import com.example.personality.ai.FeedbackPromptContext;
import com.example.personality.ai.TextFeedbackGenerator;
import com.example.personality.ai.TextFeedbackResult;
import com.example.personality.ai.TravelIntentOperation;
import com.example.personality.entity.QuestionScale;
import com.example.personality.entity.Recommendation;
import com.example.personality.entity.RecommendationFeedback;
import com.example.personality.entity.RecommendationFeedbackEvent;
import com.example.personality.entity.TestSession;
import com.example.personality.exception.AiServiceException;
import com.example.personality.repository.PlaceRepository;
import com.example.personality.repository.RecommendationFeedbackEventRepository;
import com.example.personality.repository.RecommendationFeedbackRepository;
import com.example.personality.repository.RecommendationRepository;
import com.example.personality.repository.TestSessionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 自然语言反馈事件的真实 PostgreSQL 持久化测试。
 *
 * <p>这里不 mock Repository：测试要守住的是 Java 实体、Hibernate 的 JSONB 映射、
 * Flyway V13 表结构三者之间的真实协议。</p>
 */
@SpringBootTest(properties = {
        "app.ai.enabled=true",
        "app.ai.api-key=server-test-key",
        "app.ai.allow-user-keys=true"
})
class TravelFeedbackEventIntegrationTest extends IntegrationTestBase {

    private MockHttpSession login;

    @Autowired
    RecommendationFeedbackEventRepository feedbackEventRepository;

    @Autowired
    RecommendationFeedbackRepository feedbackRepository;

    @Autowired
    TestSessionRepository sessionRepository;

    @Autowired
    RecommendationRepository recommendationRepository;

    @Autowired
    PlaceRepository placeRepository;

    @Autowired
    EntityManager entityManager;

    @Autowired
    FeedbackEventGeneratorStub feedbackGenerator;

    @BeforeEach
    void prepareOwnerAndGenerator() throws Exception {
        login = registerAndLogin(uniqueUsername("feedback_owner"), "password123");
        feedbackGenerator.calls.set(0);
        feedbackGenerator.next = TextFeedbackResult.empty();
        feedbackGenerator.failure = false;
    }

    @Test
    @DisplayName("MIXED 反馈落库后，原话、语义信号和结构化操作完整保留")
    void persistsValidatedFeedbackEvent() {
        TestSession session = sessionRepository.saveAndFlush(
                TestSession.start(null, QuestionScale.TRAVEL));
        Long placeId = placeRepository.findByCity("杭州").get(0).getId();
        Recommendation recommendation = recommendationRepository.saveAndFlush(
                Recommendation.of(
                        session.getId(), 1, 1, placeId, new BigDecimal("0.500"),
                        new Recommendation.Factors(
                                new BigDecimal("0.500"), BigDecimal.ONE, BigDecimal.ONE,
                                BigDecimal.ONE, BigDecimal.ONE)));

        JsonNode signals = objectMapper.readTree("[\"TOO_FAR\"]");
        JsonNode operations = objectMapper.readTree(
                "[{\"op\":\"ADD_PREFERENCE\",\"value\":\"TIRED\"}]");
        RecommendationFeedbackEvent event = RecommendationFeedbackEvent.create(
                session.getId(), recommendation.getId(), "这个不错，但我有点累",
                TravelFeedbackKind.MIXED, RecommendationFeedback.Reaction.LIKE,
                signals, operations);

        feedbackEventRepository.saveAndFlush(event);
        // 清空一级缓存，确保下面的查询真的从 PostgreSQL 读回并反序列化 JSONB。
        entityManager.clear();

        List<RecommendationFeedbackEvent> saved =
                feedbackEventRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        assertThat(saved).singleElement().satisfies(actual -> {
            assertThat(actual.getId()).isNotNull();
            assertThat(actual.getSessionId()).isEqualTo(session.getId());
            assertThat(actual.getRecommendationId()).isEqualTo(recommendation.getId());
            assertThat(actual.getRawText()).isEqualTo("这个不错，但我有点累");
            assertThat(actual.getFeedbackKind()).isEqualTo(TravelFeedbackKind.MIXED);
            assertThat(actual.getPlaceReaction()).isEqualTo(RecommendationFeedback.Reaction.LIKE);
            assertThat(actual.getFeedbackSignals()).isEqualTo(signals);
            assertThat(actual.getStructuredOperations()).isEqualTo(operations);
            assertThat(actual.getCreatedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("登录本人无需令牌即可创建反馈事件并返回受控响应")
    void createsFeedbackEventForOwnedSession() throws Exception {
        TestSessionRef ref = createTravelSession(login);
        long recommendationId = createRecommendation(ref.id());
        feedbackGenerator.next = new TextFeedbackResult(
                com.example.personality.ai.TravelFeedbackKind.MIXED,
                RecommendationFeedback.Reaction.LIKE,
                Set.of(com.example.personality.ai.TravelFeedbackSignal.TOO_FAR),
                List.of(TravelIntentOperation.setConstraint(
                        TravelIntentOperation.ConstraintKey.MAX_DISTANCE_METERS, 3000)),
                List.of(), "我收到了这条反馈", true, false);

        // 保留未提交会话也能反馈的行为；这里仅补上真实登录归属，不人为提交画像。
        MockHttpServletRequestBuilder request = feedbackRequest(ref.id(), null,
                feedbackBody(recommendationId, "这个不错，但太远了"));
        String response = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").isNumber())
                .andExpect(jsonPath("$.kind").value("MIXED"))
                .andExpect(jsonPath("$.reaction").value("LIKE"))
                .andExpect(jsonPath("$.summary").value("我收到了这条反馈"))
                .andExpect(jsonPath("$.shouldRecommend").value(true))
                .andExpect(jsonPath("$.excludeSeen").value(false))
                .andExpect(jsonPath("$.operations[0].op").value("SET_CONSTRAINT"))
                .andExpect(jsonPath("$.operations[0].key").value("maxDistanceMeters"))
                .andExpect(jsonPath("$.operations[0].value").value(3000.0))
                .andReturn().getResponse().getContentAsString();

        assertThat(response)
                .doesNotContain("user-secret-key")
                .doesNotContain("server-test-key")
                .doesNotContain("原始模型 JSON");
        assertThat(feedbackEventRepository.findBySessionIdOrderByCreatedAtAsc(ref.id()))
                .hasSize(1);
    }

    @Test
    @DisplayName("匿名反馈缺少、非法、错误或正确令牌均返回 401")
    void rejectsAnonymousFeedbackRegardlessOfSessionToken() throws Exception {
        TestSessionRef owner = createTravelSession(login);
        String body = feedbackBody(null, "换一个吧");

        mockMvc.perform(post("/api/travel/sessions/{id}/feedback-events", owner.id())
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/travel/sessions/{id}/feedback-events", owner.id())
                        .with(csrf()).header("X-Session-Token", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        TestSessionRef other = createTravelSession(null);
        for (String token : List.of(other.token(), owner.token())) {
            mockMvc.perform(post("/api/travel/sessions/{id}/feedback-events", owner.id())
                            .with(csrf()).header("X-Session-Token", token)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }
        assertThat(feedbackGenerator.calls).hasValue(0);
        assertThat(feedbackEventRepository.findBySessionIdOrderByCreatedAtAsc(owner.id())).isEmpty();
    }

    @Test
    @DisplayName("登录非本人即使拿到正确令牌也不能创建反馈事件")
    void rejectsForeignSessionEvenWithLeakedCorrectToken() throws Exception {
        TestSessionRef owner = createTravelSession(login);
        MockHttpSession attackerLogin = registerAndLogin(uniqueUsername("feedback_attacker"), "password123");
        TestSessionRef other = createTravelSession(attackerLogin);
        String body = feedbackBody(null, "换一个吧");

        mockMvc.perform(post("/api/travel/sessions/{id}/feedback-events", owner.id())
                        .with(csrf()).session(attackerLogin)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        for (String token : List.of(other.token(), owner.token(), "not-a-uuid")) {
            mockMvc.perform(post("/api/travel/sessions/{id}/feedback-events", owner.id())
                            .with(csrf()).session(attackerLogin).header("X-Session-Token", token)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNotFound());
        }

        assertThat(feedbackGenerator.calls).hasValue(0);
        assertThat(feedbackEventRepository.findBySessionIdOrderByCreatedAtAsc(owner.id())).isEmpty();
    }

    @Test
    @DisplayName("推荐记录不属于当前会话时返回 404 且不调用模型")
    void rejectsRecommendationFromAnotherSession() throws Exception {
        TestSessionRef owner = createTravelSession(login);
        TestSessionRef other = createTravelSession(null);
        long foreignRecommendation = createRecommendation(other.id());
        feedbackGenerator.calls.set(0);

        mockMvc.perform(post("/api/travel/sessions/{id}/feedback-events", owner.id())
                        .with(csrf()).session(login).header("X-Session-Token", owner.token())
                        .header("X-AI-Provider", "DEEPSEEK")
                        .header("X-AI-Key", "user-secret-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feedbackBody(foreignRecommendation, "这家太远了")))
                .andExpect(status().isNotFound());

        assertThat(feedbackGenerator.calls).hasValue(0);
        assertThat(feedbackEventRepository.findBySessionIdOrderByCreatedAtAsc(owner.id())).isEmpty();
    }

    @Test
    @DisplayName("空文本和超长文本返回 400 且不调用模型")
    void rejectsInvalidText() throws Exception {
        TestSessionRef ref = createTravelSession(login);
        feedbackGenerator.calls.set(0);

        mockMvc.perform(post("/api/travel/sessions/{id}/feedback-events", ref.id())
                        .with(csrf()).session(login).header("X-Session-Token", ref.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feedbackBody(null, "   ")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/travel/sessions/{id}/feedback-events", ref.id())
                        .with(csrf()).session(login).header("X-Session-Token", ref.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feedbackBody(null, "累".repeat(201))))
                .andExpect(status().isBadRequest());

        assertThat(feedbackGenerator.calls).hasValue(0);
        assertThat(feedbackEventRepository.findBySessionIdOrderByCreatedAtAsc(ref.id())).isEmpty();
    }

    @Test
    @DisplayName("非本人携带正确或非法令牌加坏 JSON 仍先返回 404")
    void foreignSessionWithMalformedJsonIsNotParsed() throws Exception {
        TestSessionRef ref = createTravelSession(login);
        MockHttpSession attackerLogin = registerAndLogin(uniqueUsername("feedback_bad_json"), "password123");

        for (String token : List.of(ref.token(), "not-a-uuid")) {
            mockMvc.perform(post("/api/travel/sessions/{id}/feedback-events", ref.id())
                            .with(csrf()).session(attackerLogin).header("X-Session-Token", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{bad json"))
                    .andExpect(status().isNotFound());
        }

        assertThat(feedbackGenerator.calls).hasValue(0);
        assertThat(feedbackEventRepository.findBySessionIdOrderByCreatedAtAsc(ref.id())).isEmpty();
    }

    @Test
    @DisplayName("非本人携带正确或非法令牌加非法字段仍先返回 404")
    void foreignSessionWithInvalidFieldsIsNotValidated() throws Exception {
        TestSessionRef ref = createTravelSession(login);
        MockHttpSession attackerLogin = registerAndLogin(uniqueUsername("feedback_bad_fields"), "password123");

        for (String token : List.of(ref.token(), "not-a-uuid")) {
            mockMvc.perform(post("/api/travel/sessions/{id}/feedback-events", ref.id())
                            .with(csrf()).session(attackerLogin).header("X-Session-Token", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(feedbackBody(null, "   ")))
                    .andExpect(status().isNotFound());
        }

        assertThat(feedbackGenerator.calls).hasValue(0);
        assertThat(feedbackEventRepository.findBySessionIdOrderByCreatedAtAsc(ref.id())).isEmpty();
    }

    @Test
    @DisplayName("匿名请求加坏 JSON 仍先返回 401")
    void anonymousRequestWithMalformedJsonIsNotParsed() throws Exception {
        TestSessionRef ref = createTravelSession(login);

        mockMvc.perform(post("/api/travel/sessions/{id}/feedback-events", ref.id())
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{bad json"))
                .andExpect(status().isUnauthorized());

        assertThat(feedbackGenerator.calls).hasValue(0);
        assertThat(feedbackEventRepository.findBySessionIdOrderByCreatedAtAsc(ref.id())).isEmpty();
    }

    @Test
    @DisplayName("登录本人加坏 JSON 返回 400")
    void ownerWithMalformedJsonIsBadRequest() throws Exception {
        TestSessionRef ref = createTravelSession(login);

        mockMvc.perform(post("/api/travel/sessions/{id}/feedback-events", ref.id())
                        .with(csrf()).session(login).header("X-Session-Token", ref.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{bad json"))
                .andExpect(status().isBadRequest());

        assertThat(feedbackEventRepository.findBySessionIdOrderByCreatedAtAsc(ref.id())).isEmpty();
    }

    @Test
    @DisplayName("登录本人加非法字段返回 400")
    void ownerWithInvalidFieldsIsBadRequest() throws Exception {
        TestSessionRef ref = createTravelSession(login);

        mockMvc.perform(post("/api/travel/sessions/{id}/feedback-events", ref.id())
                        .with(csrf()).session(login).header("X-Session-Token", ref.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feedbackBody(null, "   ")))
                .andExpect(status().isBadRequest());

        assertThat(feedbackEventRepository.findBySessionIdOrderByCreatedAtAsc(ref.id())).isEmpty();
    }

    @Test
    @DisplayName("登录本人加超大请求体返回 400 且不落事件")
    void ownerWithOversizedBodyIsBadRequest() throws Exception {
        TestSessionRef ref = createTravelSession(login);
        String oversizedBody = feedbackBody(null, "换一个吧") + " ".repeat(8192);

        mockMvc.perform(post("/api/travel/sessions/{id}/feedback-events", ref.id())
                        .with(csrf()).session(login).header("X-Session-Token", ref.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversizedBody))
                .andExpect(status().isBadRequest());

        assertThat(feedbackEventRepository.findBySessionIdOrderByCreatedAtAsc(ref.id())).isEmpty();
    }

    @Test
    @DisplayName("反馈拒绝非 JSON Content-Type，非本人即使持有正确令牌仍先返回 404")
    void rejectsNonJsonContentTypeAfterAccessGuard() throws Exception {
        TestSessionRef ref = createTravelSession(login);
        MockHttpSession attackerLogin = registerAndLogin(uniqueUsername("feedback_bad_type"), "password123");
        String body = feedbackBody(null, "换一个吧");

        mockMvc.perform(post("/api/travel/sessions/{id}/feedback-events", ref.id())
                        .with(csrf()).session(login).header("X-Session-Token", ref.token())
                        .contentType(MediaType.TEXT_PLAIN).content(body))
                .andExpect(status().isUnsupportedMediaType());
        for (String wildcardType : List.of("*/*", "application/*")) {
            mockMvc.perform(post("/api/travel/sessions/{id}/feedback-events", ref.id())
                            .with(csrf()).session(login).header("X-Session-Token", ref.token())
                            .contentType(wildcardType).content(body))
                    .andExpect(status().isUnsupportedMediaType());
            for (String token : List.of(ref.token(), "not-a-uuid")) {
                mockMvc.perform(post("/api/travel/sessions/{id}/feedback-events", ref.id())
                                .with(csrf()).session(attackerLogin).header("X-Session-Token", token)
                                .contentType(wildcardType).content(body))
                        .andExpect(status().isNotFound());
            }
        }

        assertThat(feedbackEventRepository.findBySessionIdOrderByCreatedAtAsc(ref.id())).isEmpty();
    }

    @Test
    @DisplayName("非法 AI 厂商返回 400 且不落事件")
    void rejectsInvalidAiProviderBeforePersistence() throws Exception {
        TestSessionRef ref = createTravelSession(login);
        feedbackGenerator.calls.set(0);

        mockMvc.perform(post("/api/travel/sessions/{id}/feedback-events", ref.id())
                        .with(csrf()).session(login).header("X-Session-Token", ref.token())
                        .header("X-AI-Provider", "http://127.0.0.1:8080")
                        .header("X-AI-Key", "user-secret-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feedbackBody(null, "换一个吧")))
                .andExpect(status().isBadRequest());

        assertThat(feedbackGenerator.calls).hasValue(0);
        assertThat(feedbackEventRepository.findBySessionIdOrderByCreatedAtAsc(ref.id())).isEmpty();
    }

    @Test
    @DisplayName("上游解析失败返回 502 且不落事件")
    void mapsUpstreamFailureTo502WithoutEvent() throws Exception {
        TestSessionRef ref = createTravelSession(login);
        long recommendationId = createRecommendation(ref.id());
        feedbackRepository.saveAndFlush(RecommendationFeedback.of(
                recommendationId, RecommendationFeedback.Reaction.DISLIKE));
        feedbackGenerator.failure = true;

        try {
            mockMvc.perform(post("/api/travel/sessions/{id}/feedback-events", ref.id())
                        .with(csrf()).session(login).header("X-Session-Token", ref.token())
                        .header("X-AI-Provider", "DEEPSEEK")
                        .header("X-AI-Key", "user-secret-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feedbackBody(recommendationId, "换一个吧")))
                    .andExpect(status().isBadGateway());

            assertThat(feedbackEventRepository.findBySessionIdOrderByCreatedAtAsc(ref.id())).isEmpty();
            assertThat(feedbackRepository.findByRecommendationId(recommendationId))
                    .get()
                    .extracting(RecommendationFeedback::getReaction)
                    .isEqualTo(RecommendationFeedback.Reaction.DISLIKE);
        } finally {
            feedbackGenerator.failure = false;
        }
    }

    private long createRecommendation(long sessionId) {
        Long placeId = placeRepository.findByCity("杭州").get(0).getId();
        return recommendationRepository.saveAndFlush(Recommendation.of(
                sessionId, 1, 1, placeId, new BigDecimal("0.500"),
                new Recommendation.Factors(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ONE, BigDecimal.ONE))).getId();
    }

    private String feedbackBody(Long recommendationId, String text) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("text", text);
        body.put("recommendationId", recommendationId);
        body.put("query", Map.of(
                "intents", List.of("HUNGRY"),
                "preferences", List.of("QUIET"),
                "durationMinutes", 60,
                "maxDistanceMeters", 5000,
                "budgetMax", 100,
                "biases", Map.of()));
        body.put("latitude", 30.25);
        body.put("longitude", 120.16);
        return json(body);
    }

    private MockHttpServletRequestBuilder feedbackRequest(long sessionId, String token, String body) {
        MockHttpServletRequestBuilder request = post("/api/travel/sessions/{id}/feedback-events", sessionId)
                .with(csrf()).session(login)
                .header("X-AI-Provider", "DEEPSEEK")
                .header("X-AI-Key", "user-secret-key")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return token == null ? request : request.header("X-Session-Token", token);
    }

    @TestConfiguration
    static class FeedbackGeneratorConfiguration {

        @Bean
        @Primary
        FeedbackEventGeneratorStub feedbackEventGenerator() {
            return new FeedbackEventGeneratorStub();
        }
    }

    static final class FeedbackEventGeneratorStub implements TextFeedbackGenerator {

        final AtomicInteger calls = new AtomicInteger();
        volatile TextFeedbackResult next = TextFeedbackResult.empty();
        volatile boolean failure;

        @Override
        public TextFeedbackResult interpret(AiCredentials credentials, FeedbackPromptContext context) {
            calls.incrementAndGet();
            if (failure) {
                throw new AiServiceException("上游反馈解析失败");
            }
            return next;
        }
    }
}
