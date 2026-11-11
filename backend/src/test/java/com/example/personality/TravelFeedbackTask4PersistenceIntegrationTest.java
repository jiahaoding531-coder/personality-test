package com.example.personality;

import com.example.personality.ai.AiCredentials;
import com.example.personality.ai.TextFeedbackGenerator;
import com.example.personality.ai.TextFeedbackResult;
import com.example.personality.ai.TravelFeedbackKind;
import com.example.personality.dto.TravelQuerySnapshot;
import com.example.personality.domain.TravelDimension;
import com.example.personality.entity.QuestionScale;
import com.example.personality.entity.Recommendation;
import com.example.personality.entity.RecommendationFeedback;
import com.example.personality.entity.TestSession;
import com.example.personality.repository.PlaceRepository;
import com.example.personality.repository.RecommendationFeedbackEventRepository;
import com.example.personality.repository.RecommendationFeedbackRepository;
import com.example.personality.repository.RecommendationRepository;
import com.example.personality.repository.TestSessionRepository;
import com.example.personality.service.SaveFeedbackEventCommand;
import com.example.personality.service.SavedFeedbackEvent;
import com.example.personality.service.TravelFeedbackEventService;
import com.example.personality.service.TravelFeedbackPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 4 的真实 Spring/JPA 边界测试。
 *
 * <p>此类刻意不继承 {@link IntegrationTestBase}，也不加 {@code @Transactional}：
 * 每个 Repository 调用和反馈服务的两个短事务都真实提交。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TravelFeedbackTask4PersistenceIntegrationTest.StubGeneratorConfig.class)
class TravelFeedbackTask4PersistenceIntegrationTest {

    @Autowired
    private TravelFeedbackEventService eventService;

    @Autowired
    private TravelFeedbackPersistence persistence;

    @Autowired
    private TestSessionRepository sessionRepository;

    @Autowired
    private RecommendationRepository recommendationRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private RecommendationFeedbackEventRepository eventRepository;

    @Autowired
    private RecommendationFeedbackRepository feedbackRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void springManagedServiceCallsGeneratorOutsideTransaction() {
        Fixture fixture = fixture();
        try {
            SavedFeedbackEvent saved = eventService.create(
                    fixture.session().getId(), fixture.recommendation().getId(),
                    "这个不错", query(), 30.2420, 120.1400,
                    AiCredentials.forServer("http://unused", "test", "test"));

            assertThat(saved.eventId()).isNotNull();
            assertThat(eventRepository.findById(saved.eventId())).isPresent();
            assertThat(feedbackRepository.findByRecommendationId(
                    fixture.recommendation().getId()))
                    .get()
                    .extracting(RecommendationFeedback::getReaction)
                    .isEqualTo(RecommendationFeedback.Reaction.LIKE);
            assertThat(StubGeneratorConfig.called.get()).isTrue();
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    void eventAndReactionRollbackTogetherWhenReactionConstraintFails() {
        Fixture fixture = fixture();
        String constraintName = "ck_task4_forced_reaction_failure";
        try {
            // NOT VALID 允许测试库中已有旧行，但会拒绝本次新插入的 reaction。
            jdbcTemplate.execute("ALTER TABLE recommendation_feedback "
                    + "ADD CONSTRAINT " + constraintName
                    + " CHECK (false) NOT VALID");

            TextFeedbackResult result = likeResult();
            assertThatThrownBy(() -> persistence.saveEventAndOptionalReaction(
                    new SaveFeedbackEventCommand(
                            fixture.session().getId(), fixture.recommendation().getId(),
                            "这个不错", result)))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(eventRepository.findBySessionIdOrderByCreatedAtAsc(
                    fixture.session().getId())).isEmpty();
            assertThat(feedbackRepository.findByRecommendationId(
                    fixture.recommendation().getId())).isEmpty();
        } finally {
            jdbcTemplate.execute("ALTER TABLE recommendation_feedback DROP CONSTRAINT "
                    + constraintName);
            cleanup(fixture);
        }
    }

    private Fixture fixture() {
        TestSession session = sessionRepository.saveAndFlush(
                TestSession.start(null, QuestionScale.TRAVEL));
        Long placeId = placeRepository.findByCity("杭州").get(0).getId();
        Recommendation recommendation = recommendationRepository.saveAndFlush(
                Recommendation.of(session.getId(), 1, 1, placeId,
                        new BigDecimal("0.500"),
                        new Recommendation.Factors(
                                new BigDecimal("0.500"), BigDecimal.ONE,
                                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)));
        return new Fixture(session, recommendation);
    }

    private void cleanup(Fixture fixture) {
        sessionRepository.deleteById(fixture.session().getId());
    }

    private static TravelQuerySnapshot query() {
        return new TravelQuerySnapshot(
                List.of(), List.of(), 120, 5000, 100,
                Map.of(TravelDimension.FOOD, 0.5));
    }

    private static TextFeedbackResult likeResult() {
        return new TextFeedbackResult(
                TravelFeedbackKind.PLACE_OPINION,
                RecommendationFeedback.Reaction.LIKE,
                Set.of(), List.of(), List.of(), "喜欢这里", false, false);
    }

    private record Fixture(TestSession session, Recommendation recommendation) {
    }

    @TestConfiguration
    static class StubGeneratorConfig {

        private static final AtomicBoolean called = new AtomicBoolean();

        @Bean
        @Primary
        TextFeedbackGenerator textFeedbackGenerator() {
            return (credentials, context) -> {
                assertThat(TransactionSynchronizationManager
                        .isActualTransactionActive()).isFalse();
                called.set(true);
                return likeResult();
            };
        }
    }
}
