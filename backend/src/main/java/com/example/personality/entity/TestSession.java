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
 * 一次人格测试会话，对应 test_sessions 表。
 *
 * <p>一次会话 = 用户从"开始测试"到"提交"的完整过程，中间可以中断、分多次提交答案
 * （answers 表逐题保存），最后统一 submit 触发计分。
 */
@Entity
@Table(name = "test_sessions")
public class TestSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 关联用户。V0.1 不做登录，允许为 null。
     *
     * <p>注意这里存的是<b>裸的 Long</b>，而不是 {@code @ManyToOne User user}。
     * 这是刻意的设计选择——用关联对象会引入三样新手极难 debug 的东西：
     * <ol>
     *   <li><b>懒加载代理</b>：拿到的是 Hibernate 生成的子类代理，断点里看到的
     *       对象长得不对劲，访问字段还可能抛 LazyInitializationException</li>
     *   <li><b>N+1 查询</b>：序列化时每个 session 都偷偷再查一次 user 表</li>
     *   <li><b>循环序列化</b>：User 里有 sessions、Session 里有 user，JSON 序列化直接栈溢出</li>
     * </ol>
     * V0.1 根本不需要"从会话导航到用户"这个能力，所以直接用外键值最省心。
     * 真需要关联查询时再显式写 join，不要图省事用对象关联。
     */
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SessionStatus status = SessionStatus.IN_PROGRESS;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    protected TestSession() {
    }

    /** 开启一次新会话。 */
    public static TestSession start(Long userId) {
        TestSession session = new TestSession();
        session.userId = userId;
        session.status = SessionStatus.IN_PROGRESS;
        return session;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * 标记会话已提交。
     *
     * <p>把状态变更收拢成一个方法，而不是对外暴露 {@code setStatus()}，
     * 好处是"提交"这件事的完整语义（改状态 + 记录时间）只有一个入口，
     * 不会出现"改了状态却忘了记时间"的情况。这叫"富领域模型"。
     */
    public void markSubmitted() {
        this.status = SessionStatus.SUBMITTED;
        this.submittedAt = Instant.now();
    }

    public boolean isSubmitted() {
        return this.status == SessionStatus.SUBMITTED;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }
}
