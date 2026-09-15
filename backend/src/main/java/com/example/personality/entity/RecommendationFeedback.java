package com.example.personality.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 用户对一条推荐的反应，对应 recommendation_feedback 表。
 *
 * <p><b>这是整个闭环里最有价值的数据。</b>
 *
 * <p>计划书第七节的产品原则第一条：
 * <blockquote>测试只是初始猜测，真实行为和反馈更重要。</blockquote>
 *
 * <p>问卷只有 8 道题，每个维度只有 5 个可能取值——粒度很粗。
 * 真正让画像变准的是这里的反馈：用户点一次"不喜欢"，
 * 那是一个比问卷答案可信得多的信号，因为它是**真实行为**而非自我描述。
 *
 * <p>V0 只采集不改画像。等攒够数据再让反馈反哺偏好（属于计划书的 Phase 6）。
 */
@Entity
@Table(name = "recommendation_feedback")
public class RecommendationFeedback {

    /** 用户对一条推荐的反应。 */
    public enum Reaction {
        LIKE("喜欢"),
        DISLIKE("不喜欢");

        private final String label;

        Reaction(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recommendation_id", nullable = false, unique = true)
    private Long recommendationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reaction", nullable = false, length = 16)
    private Reaction reaction;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RecommendationFeedback() {
    }

    public static RecommendationFeedback of(Long recommendationId, Reaction reaction) {
        RecommendationFeedback f = new RecommendationFeedback();
        f.recommendationId = recommendationId;
        f.reaction = reaction;
        return f;
    }

    /** 用户改主意时更新，而不是插入第二条——否则统计会被重复数据污染。 */
    public void changeReaction(Reaction newReaction) {
        this.reaction = newReaction;
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

    public Long getRecommendationId() {
        return recommendationId;
    }

    public Reaction getReaction() {
        return reaction;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
