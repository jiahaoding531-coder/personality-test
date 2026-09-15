package com.example.personality.entity;

import com.example.personality.domain.Dimension;
import com.example.personality.domain.DimensionScore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * 一次测试算出的 5 维人格画像，对应 personality_profiles 表。
 *
 * <p>分数已经归一化到 0.00 ~ 100.00。数据库列类型是 {@code NUMERIC(5,2)}，
 * Java 侧对应 {@link BigDecimal}——不是 double。
 *
 * <p><b>为什么用 BigDecimal 而不是 double：</b>
 * {@code 0.1 + 0.2} 在 double 下等于 {@code 0.30000000000000004}。
 * 人格分数要展示给用户、要比较大小、要写进测试断言，这些场景下浮点误差
 * 会带来持续不断的困扰（比如 87.5 存进去读出来变成 87.49999999999999）。
 * 凡是对精度有要求的金额、分数、比例，一律用 BigDecimal。
 */
@Entity
@Table(name = "personality_profiles")
public class PersonalityProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 一个会话只能有一份画像。
     * {@code unique = true} 是幂等性的第二道防线（第一道是 TestSession.status）。
     */
    @Column(name = "session_id", nullable = false, unique = true)
    private Long sessionId;

    @Column(name = "openness", nullable = false, precision = 5, scale = 2)
    private BigDecimal openness;

    @Column(name = "extraversion", nullable = false, precision = 5, scale = 2)
    private BigDecimal extraversion;

    @Column(name = "conscientiousness", nullable = false, precision = 5, scale = 2)
    private BigDecimal conscientiousness;

    @Column(name = "agreeableness", nullable = false, precision = 5, scale = 2)
    private BigDecimal agreeableness;

    @Column(name = "emotional_stability", nullable = false, precision = 5, scale = 2)
    private BigDecimal emotionalStability;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PersonalityProfile() {
    }

    /**
     * 从计分结果构建画像实体。
     *
     * <p>这里做了一次"维度枚举 → 数据库列"的映射。因为 5 个维度是各自独立的列
     * 而不是"一个 dimension 列 + 一个 score 列"的行式结构，必须一个个取出来赋值。
     *
     * <p>调用前请确保 scores 里 5 个维度齐全——ScoringService 已经保证了这一点。
     */
    public static PersonalityProfile from(Long sessionId, Map<Dimension, DimensionScore<Dimension>> scores) {
        PersonalityProfile profile = new PersonalityProfile();
        profile.sessionId = sessionId;
        profile.openness = normalizedOf(scores, Dimension.OPENNESS);
        profile.extraversion = normalizedOf(scores, Dimension.EXTRAVERSION);
        profile.conscientiousness = normalizedOf(scores, Dimension.CONSCIENTIOUSNESS);
        profile.agreeableness = normalizedOf(scores, Dimension.AGREEABLENESS);
        profile.emotionalStability = normalizedOf(scores, Dimension.EMOTIONAL_STABILITY);
        return profile;
    }

    private static BigDecimal normalizedOf(Map<Dimension, DimensionScore<Dimension>> scores, Dimension dimension) {
        DimensionScore<Dimension> score = scores.get(dimension);
        if (score == null) {
            // 正常情况下不会发生（ScoringService 会先拦），这里只是最后一道防御
            throw new IllegalStateException("计分结果缺少维度：" + dimension.label());
        }
        return score.normalized();
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** 按维度取分。用于把实体转成 API 响应时遍历 5 个维度。 */
    public BigDecimal scoreOf(Dimension dimension) {
        return switch (dimension) {
            case OPENNESS -> openness;
            case EXTRAVERSION -> extraversion;
            case CONSCIENTIOUSNESS -> conscientiousness;
            case AGREEABLENESS -> agreeableness;
            case EMOTIONAL_STABILITY -> emotionalStability;
        };
    }

    public Long getId() {
        return id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
