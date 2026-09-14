package com.example.personality.security;

import com.example.personality.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * 本项目自己的 {@link UserDetails} 实现。
 *
 * <h2>为什么不直接用 Spring 自带的 {@code User}？</h2>
 *
 * <p>Spring 提供的 {@code org.springframework.security.core.userdetails.User}
 * 只有用户名、密码、权限三样东西，<b>没有地方放业务主键</b>。
 *
 * <p>如果用它，Controller 里想知道"当前用户的 id 是多少"就只能再查一次数据库：
 * <pre>
 *   // ❌ 每次都要多一次查询
 *   Long userId = userService.requireByUsername(principal.getUsername()).getId();
 * </pre>
 *
 * <p>自定义 principal 之后，id 在登录时就已经装在身份对象里了：
 * <pre>
 *   // ✅ 零额外查询
 *   Long userId = principal.getId();
 * </pre>
 *
 * <p>认证信息本来就是"这次请求的身份"——把 id 一起带着是最自然的做法。
 * 多查一次库在单机上无所谓，但这个开销会随**每一个**需要用户身份的请求
 * 累积，而且完全没必要。
 *
 * <h2>为什么放在 security 包而不是 entity 包</h2>
 *
 * <p>它实现的是 Spring Security 的接口，属于"框架适配层"。
 * {@code entity/User} 是纯净的领域模型，不该被框架接口污染——
 * 两者的职责不同，分开放才能各自独立演进。
 */
public class AppUserPrincipal implements UserDetails {

    private final Long id;
    private final String username;

    /**
     * 密码哈希。
     *
     * <p>认证完成之后其实用不到了，但 {@link UserDetails} 接口要求必须有。
     * 注意它<b>永远不会</b>被序列化进 JSON——序列化走的是 {@code UserResponse}。
     */
    private final String passwordHash;

    public AppUserPrincipal(Long id, String username, String passwordHash) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
    }

    public static AppUserPrincipal from(User user) {
        return new AppUserPrincipal(user.getId(), user.getUsername(), user.getPasswordHash());
    }

    /** 业务主键。认证后可以直接取用，不需要再查库。 */
    public Long getId() {
        return id;
    }

    // ---------- UserDetails 接口要求实现的方法 ----------

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // 本项目只有普通用户一种角色，没有管理员后台。
        // 真要做角色区分时，这里从数据库读权限列表即可。
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    /**
     * 下面四个方法返回 true，表示"账号没过期、没锁定、凭证没过期、是启用的"。
     *
     * <p>本例没有实现这些状态，所以一律放行。真实系统里如果需要
     * "封禁用户"或"强制改密码"的功能，就在这里读对应的字段——
     * Spring Security 会自动拒绝这些账号，不需要在业务代码里到处判断。
     */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
