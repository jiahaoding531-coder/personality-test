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
 * 用户对某一道题的一次作答，对应 answers 表。
 *
 * <p>存的是用户<b>原始选择</b>（1~5），不是反转后的分。
 * 反向计分的翻转发生在算分的时候（ScoringService），不在存储的时候。
 * 这样原始数据永远保真——如果将来发现某个维度定义得不对，
 * 可以直接用历史重新算，不需要让用户重做测试。
 */
@Entity
@Table(name = "answers")
public class Answer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    /** 用户选择的原始分值，1~5。数据库上有 CHECK 约束兜底。 */
    @Column(name = "score", nullable = false)
    private int score;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Answer() {
    }

    public static Answer of(Long sessionId, Long questionId, int score) {
        Answer answer = new Answer();
        answer.sessionId = sessionId;
        answer.questionId = questionId;
        answer.score = score;
        return answer;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * 修改已有作答的分值。
     *
     * <p>用于"用户返回上一题改了答案"的场景。配合 answers 表上
     * {@code (session_id, question_id)} 的唯一约束使用。
     */
    public void changeScore(int newScore) {
        this.score = newScore;
    }

    public Long getId() {
        return id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public int getScore() {
        return score;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
