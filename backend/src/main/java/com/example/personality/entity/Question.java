package com.example.personality.entity;

import com.example.personality.domain.Dimension;
import com.example.personality.domain.TravelDimension;
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
 * 题库中的一道题，对应 questions 表。
 *
 * <p>这个类几乎是只读的——题目由 Flyway 的 V2 脚本灌入，运行时不增删改。
 * V0.1 不做后台管理题目，所以没有 setter。
 */
@Entity
@Table(name = "questions")
public class Question {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content", nullable = false, length = 500)
    private String content;

    /**
     * 所属维度。
     *
     * <p><b>⚠️ 这里存的是字符串而不是枚举，是刻意的。</b>
     *
     * <p>因为一个问题可能属于两套量表之一：人格题是 {@code Dimension}
     * （OPENNESS、EXTRAVERSION…），旅行题是 {@code TravelDimension}
     * （NATURE、FOOD…）。用一个枚举字段装不下。
     *
     * <p>直接写 {@code @Enumerated} 会在读取旅行题时抛
     * {@code No enum constant Dimension.NATURE}——而且是在序列化时才炸，
     * 表现为接口 500，排查起来得绕一圈。
     *
     * <p>取值由应用层的枚举保证（见 {@link #getPersonalityDimension()} 和
     * {@link #getTravelDimension()}）。用具体方法解析而不是到处
     * {@code valueOf}，好处是**解析失败时的错误信息统一在这里**，
     * 而且将来加第三个量表只需改这一个文件。
     */
    @Column(name = "dimension", nullable = false, length = 32)
    private String dimension;

    /**
     * 所属量表。决定 {@link #dimension} 该按哪个枚举解析。
     *
     * <p>{@code @Enumerated(EnumType.STRING)} <b>不能省</b>：
     * JPA 默认用 {@code ORDINAL}（存枚举的声明序号）。
     * 一旦往枚举中间插一个新值，所有历史数据的含义就静默错位了——
     * 不报错，纯粹的数据损坏。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "scale", nullable = false, length = 20)
    private QuestionScale scale = QuestionScale.PERSONALITY;

    /** 为 true 时，用户对这个题的回答需要做 {@code 6 - score} 的反转。 */
    @Column(name = "reverse_scored", nullable = false)
    private boolean reverseScored;

    /** 展示顺序。维度是交错的，见 V2 脚本里的说明。 */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * JPA 要求在实体实例被"持久化"（第一次 save）之前回调这个方法。
     *
     * <p>为什么不依赖数据库的 {@code DEFAULT now()}？因为那样 Hibernate 在 insert 之后
     * 并不知道数据库填了什么值，内存里的对象仍然是 null，除非再查一次库。
     * 在 Java 侧赋值可以保证对象状态和数据库一致。
     */
    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** JPA 规范要求实体必须有一个无参构造器（可以是 protected，防止业务代码乱用）。 */
    protected Question() {
    }

    public Long getId() {
        return id;
    }

    public String getContent() {
        return content;
    }

    /** 维度的原始字符串值，如 "OPENNESS" 或 "NATURE"。 */
    public String getDimension() {
        return dimension;
    }

    public QuestionScale getScale() {
        return scale;
    }

    /** 按人格维度解析。非人格题调用会抛异常——调用前应先确认 scale。 */
    public Dimension getPersonalityDimension() {
        return Dimension.valueOf(dimension);
    }

    /** 按旅行维度解析。非旅行题调用会抛异常。 */
    public TravelDimension getTravelDimension() {
        return TravelDimension.valueOf(dimension);
    }

    /**
     * 维度的中文展示名，按量表自动选择。
     *
     * <p>放在实体上而不是让 DTO 自己判断，是为了让"维度名怎么来的"
     * 只有一个定义处。
     */
    public String getDimensionLabel() {
        return scale == QuestionScale.TRAVEL
                ? TravelDimension.valueOf(dimension).label()
                : Dimension.valueOf(dimension).label();
    }

    public boolean isReverseScored() {
        return reverseScored;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
