package com.example.personality.repository;

import com.example.personality.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 按用户名查找。
     *
     * <p>返回 {@code Optional} 而不是可空的 User——登录时"用户不存在"
     * 是完全正常的业务分支（用户输错了用户名），必须被显式处理，
     * 不能靠"忘了判空就 NPE"来发现问题。
     *
     * <p><b>⚠️ 安全提醒：登录失败时，不要区分"用户不存在"和"密码错误"。</b>
     * 如果对前者返回"该用户不存在"，攻击者就能用这个接口**枚举出系统里
     * 有哪些用户名**（这叫用户名枚举漏洞）。两者都应该返回同一句
     * "用户名或密码错误"。见 {@code UserService.authenticate}。
     */
    Optional<User> findByUsername(String username);

    /** 注册时检查用户名是否被占用。 */
    boolean existsByUsername(String username);
}
