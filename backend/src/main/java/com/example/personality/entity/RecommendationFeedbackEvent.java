package com.example.personality.entity;

import com.example.personality.ai.TravelFeedbackKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;

/**
 * 一次自然语言推荐反馈的不可变事实，对应 recommendation_feedback_events 表。
 *
 * <p>原话、语义信号和最终采用的 operations 一起保存，便于以后复盘当时的
 * 决策；用户 key、Prompt 和模型原始响应不属于业务事实，因此不会进入实体。</p>
 */
@Entity
@Table(name = "recommendation_feedback_events")
public class RecommendationFeedbackEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "recommendation_id")
    private Long recommendationId;

    @Column(name = "raw_text", nullable = false, length = 200)
    private String rawText;

    @Enumerated(EnumType.STRING)
    @Column(name = "feedback_kind", nullable = false, length = 32)
    private TravelFeedbackKind feedbackKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "place_reaction", length = 16)
    private RecommendationFeedback.Reaction placeReaction;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feedback_signals", nullable = false, columnDefinition = "jsonb")
    private JsonNode feedbackSignals;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structured_operations", nullable = false, columnDefinition = "jsonb")
    private JsonNode structuredOperations;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RecommendationFeedbackEvent() {
    }

    /**
     * 创建完整的反馈事件。两个 JSON 字段只接受数组，和数据库 CHECK 约束保持一致。
     */
    public static RecommendationFeedbackEvent create(
            Long sessionId,
            Long recommendationId,
            String rawText,
            TravelFeedbackKind feedbackKind,
            RecommendationFeedback.Reaction placeReaction,
            JsonNode feedbackSignals,
            JsonNode structuredOperations) {
        RecommendationFeedbackEvent event = new RecommendationFeedbackEvent();
        event.sessionId = sessionId;
        event.recommendationId = recommendationId;
        event.rawText = rawText;
        event.feedbackKind = feedbackKind;
        event.placeReaction = placeReaction;
        event.feedbackSignals = requireArray(feedbackSignals, "feedbackSignals").deepCopy();
        event.structuredOperations = requireArray(
                structuredOperations, "structuredOperations").deepCopy();
        return event;
    }

    /**
     * 简化入口：调用方没有独立反馈信号时，明确保存为空数组。
     */
    public static RecommendationFeedbackEvent create(
            Long sessionId,
            Long recommendationId,
            String rawText,
            TravelFeedbackKind feedbackKind,
            RecommendationFeedback.Reaction placeReaction,
            JsonNode structuredOperations) {
        return create(sessionId, recommendationId, rawText, feedbackKind, placeReaction,
                JsonNodeFactory.instance.arrayNode(), structuredOperations);
    }

    private static JsonNode requireArray(JsonNode value, String fieldName) {
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(fieldName + " 必须是 JSON 数组");
        }
        return value;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public Long getRecommendationId() {
        return recommendationId;
    }

    public String getRawText() {
        return rawText;
    }

    public TravelFeedbackKind getFeedbackKind() {
        return feedbackKind;
    }

    public RecommendationFeedback.Reaction getPlaceReaction() {
        return placeReaction;
    }

    public JsonNode getFeedbackSignals() {
        return feedbackSignals;
    }

    public JsonNode getStructuredOperations() {
        return structuredOperations;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
