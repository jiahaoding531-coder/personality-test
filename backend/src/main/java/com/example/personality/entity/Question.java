package com.example.personality.entity;

import com.example.personality.domain.Dimension;
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
     * {@code @Enumerated(EnumType.STRING)} <b>绝对不能省</b>。
     *
     * <p>JPA 的默认行为是 {@code EnumType.ORDINAL}——把枚举存成它在声明中的序号
     * （0、1、2...）。一旦你哪天调整了 Dimension 里常量的顺序，或者往中间插了一个新维度，
     * 数据库里所有历史数据的含义就全错位了，而且不会报错，是纯粹的静默数据损坏。
     *
     * <p>STRING 存的是枚举名字符串（"OPENNESS"），可读、抗重排，是本项目的正确选择。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "dimension", nullable = false, length = 32)
    private Dimension dimension;

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

    public Dimension getDimension() {
        return dimension;
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
