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
import java.util.UUID;

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

    /**
     * 会话访问令牌——持有它即可访问这个会话。
     *
     * <p><b>为什么需要它：</b>支持「不登录也能做测试」意味着这些端点必须匿名可访问，
     * 而会话 id 是自增的连续整数，猜起来毫无难度。没有令牌的话，
     * 任何人遍历 id 就能读到全站人的测试结果、甚至改写别人没提交的答案
     * （IDOR，OWASP A01）。
     *
     * <p>令牌是 128 位随机 UUID，猜不到。**知道 id 不重要，拿到令牌才算数。**
     *
     * <p>这个字段会返回给会话的创建者，但**不会**出现在任何列表接口里——
     * 历史记录只返回 {@code sessionId}，不返回令牌（自己的会话本来就凭登录身份访问）。
     */
    @Column(name = "access_token", nullable = false, unique = true)
    private UUID accessToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SessionStatus status = SessionStatus.IN_PROGRESS;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    protected TestSession() {
    }

    /** 开启一次新会话，同时生成一个随机的访问令牌。 */
    public static TestSession start(Long userId) {
        TestSession session = new TestSession();
        session.userId = userId;
        session.status = SessionStatus.IN_PROGRESS;
        // 用 UUID.randomUUID() 而不是自增或时间戳：
        // 它基于密码学安全的随机数生成器，128 位空间，猜中概率可以忽略。
        session.accessToken = UUID.randomUUID();
        return session;
    }

    /**
     * 校验令牌是否匹配。
     *
     * <p>用 {@link UUID#equals} 而不是字符串比较——UUID 类型本身就能
     * 挡住格式非法的输入（比如攻击者传个空串或超长串），
     * 不需要额外做参数校验。
     */
    public boolean matchesToken(UUID candidate) {
        return accessToken != null && accessToken.equals(candidate);
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

    public UUID getAccessToken() {
        return accessToken;
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
