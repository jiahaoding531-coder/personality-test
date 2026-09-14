package com.example.personality.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * AI 生成的个性化反馈，对应 ai_reports 表。
 *
 * <p>这张表 V0.1 就建好了（见 {@code V1__init_schema.sql}），
 * 但直到 V0.2 接入真实 AI 才开始写入——这是"先把表结构占住"的价值：
 * 接入时不需要写新的迁移脚本，也不需要改动已有的任何表。
 *
 * <p>一个 profile 可以有<b>多条</b>报告（用户点"重新生成"就会多一条），
 * 所以这里<b>没有</b>像 {@code personality_profiles} 那样加唯一约束。
 * 查询时按创建时间倒序取最新的一条。
 */
@Entity
@Table(name = "ai_reports")
public class AiReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的人格画像。同样用裸 Long，不用 @ManyToOne——理由见 TestSession 的注释。 */
    @Column(name = "profile_id", nullable = false)
    private Long profileId;

    /**
     * 反馈正文。
     *
     * <p>数据库列类型是 {@code TEXT}（不限长度），所以这里用
     * {@code columnDefinition = "TEXT"} 告诉 Hibernate 别按默认的 varchar(255)
     * 去校验。{@code ddl-auto=validate} 模式下，类型对不上会导致启动失败。
     */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * 生成这份报告用的模型名，例如 {@code deepseek:deepseek-chat}。
     *
     * <p>⚠️ 注意：这一列<b>不在</b> {@code V1__init_schema.sql} 里，
     * 是 {@code V3} 新增的。这正是 Flyway 的价值——已经上线的数据库
     * 通过新增一个版本脚本就能演进，不需要重建表、也不会丢数据。
     * <b>绝对不要去改 V1 那个已经执行过的脚本</b>，那会让所有已部署环境的
     * 校验和失败（Flyway 会记录并比对校验和）。
     */
    @Column(name = "provider", length = 64)
    private String provider;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AiReport() {
    }

    public static AiReport of(Long profileId, String content, String provider) {
        AiReport report = new AiReport();
        report.profileId = profileId;
        report.content = content;
        report.provider = provider;
        return report;
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

    public Long getProfileId() {
        return profileId;
    }

    public String getContent() {
        return content;
    }

    public String getProvider() {
        return provider;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
