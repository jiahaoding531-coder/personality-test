package com.example.personality.service;

import com.example.personality.entity.User;
import com.example.personality.repository.UserRepository;
import com.example.personality.security.AppUserPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把「数据库里的用户」翻译成「Spring Security 认识的身份」。
 *
 * <p>{@link UserDetailsService} 是 Spring Security 定义的一个极简接口，
 * 只有一个方法。整个框架只通过它来获取用户信息——
 * 也就是说，Spring Security 完全不知道你的用户存在哪张表、叫什么名字。
 * 你提供这个适配器，它负责剩下的全部流程（密码比对、权限装配、
 * 认证结果封装、异常处理）。
 *
 * <p>这是框架设计的常见手法：**在你和外框架之间放一个窄接口**，
 * 框架只依赖这个接口，你的领域模型保持干净。
 * 你在 JDBC 里用过的 {@code ResultSet} → 对象的手工映射，本质上是同一件事。
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public AppUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 按用户名加载用户。
     *
     * <p><b>注意返回的是 Spring 的 {@code UserDetails}，不是我们的 {@code User} 实体。</b>
     * 这里有个同名类的坑：{@code org.springframework.security.core.userdetails.User}
     * 和本项目的 {@code com.example.personality.entity.User} 重名。
     * 下面用全限定名写清楚了是哪一种——不要图省事去 import，那样后文会读不懂。
     *
     * <p>找不到用户时抛 {@link UsernameNotFoundException}。这看起来和
     * "返回 null"差不多，但前者是有意义的：{@code DaoAuthenticationProvider}
     * 捕获它之后会执行一次<b>假的密码比对</b>（对一个内置的假哈希做 BCrypt 校验），
     * 让"用户不存在"和"密码错误"的耗时几乎相同。
     *
     * <p>为什么要在乎这点耗时？如果用户不存在时立刻返回、密码错误时花 100ms
     * 做 BCrypt，攻击者就能通过响应时间差异**判断出哪些用户名是存在的**。
     * 这叫时序攻击，是一个真实存在且容易被忽略的漏洞面。
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在：" + username));

        // 返回我们自己的 principal，把业务主键 id 一起带上。
        // 这样后续请求想用 userId 时直接取就行，不需要再查一次库。
        return AppUserPrincipal.from(user);
    }
}
