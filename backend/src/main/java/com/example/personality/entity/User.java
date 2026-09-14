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
 * 用户账号，对应 users 表。
 *
 * <p>V1 就建好了这张表，但直到 V0.4 引入登录才真正被使用。
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    /**
     * BCrypt 哈希后的密码。
     *
     * <p><b>这个字段永远不应该出现在任何 API 响应里。</b>
     * 对外返回用户信息时一律走 {@code UserResponse}，不直接序列化实体——
     * 原因见 {@code QuestionResponse} 的注释（实体 ≠ DTO）。
     *
     * <p>哈希而不是加密：哈希是单向的，没法从哈希反推出原密码；
     * 加密是可逆的，密钥泄露就等于全盘泄露。存密码只能用哈希。
     */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected User() {
    }

    /**
     * 创建新用户。
     *
     * @param passwordHash <b>必须</b>是已经哈希过的值，绝不能传明文。
     *                     哈希的责任在 Service 层（见 UserService.register），
     *                     实体只负责存。
     */
    public static User create(String username, String passwordHash) {
        User user = new User();
        user.username = username;
        user.passwordHash = passwordHash;
        return user;
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

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
