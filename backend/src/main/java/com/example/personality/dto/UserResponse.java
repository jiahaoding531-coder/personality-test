package com.example.personality.dto;

import com.example.personality.entity.User;

import java.time.Instant;

/**
 * 对外的用户信息。
 *
 * <p><b>⚠️ 注意这里没有 passwordHash 字段</b>，而且永远不该有。
 *
 * <p>这是"实体 ≠ DTO"这条规则最关键的一次应用。如果把 {@code User} 实体
 * 直接序列化成 JSON 返回，密码哈希就会出现在响应体里——用户打开 F12
 * 就能看到自己的密码哈希，而哈希是可以离线暴力破解的。
 *
 * <p>这类事故在真实项目里一再发生，而且往往是这样来的：
 * "先直接返回实体吧，反正就一个字段不一样，回头再改"。
 * 那个"回头"通常不会到来。
 */
public record UserResponse(Long id, String username, Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getCreatedAt());
    }
}
