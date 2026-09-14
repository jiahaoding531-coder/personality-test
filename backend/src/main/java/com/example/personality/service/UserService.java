package com.example.personality.service;

import com.example.personality.entity.User;
import com.example.personality.exception.ResourceNotFoundException;
import com.example.personality.exception.UsernameTakenException;
import com.example.personality.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 用户账号的注册与查询。
 *
 * <p><b>登录不在这里。</b>认证流程交给了 Spring Security 的
 * {@code AuthenticationManager}（见 {@code AuthController.login}）——
 * 密码比对、时序攻击防护、凭证异常处理都由框架负责，
 * 自己重写一遍只会引入漏洞。
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 注册新用户。
     *
     * <p><b>哈希在这里做，不在实体里做。</b>为什么？
     * 因为 {@code User.create(username, passwordHash)} 的第二个参数
     * 从签名上就要求是"已经哈希过的值"。如果让实体自己接收明文再哈希，
     * 将来某个调用方可能会绕过实体直接建对象、把明文塞进库里。
     *
     * <p>把"必须哈希"这个约束放在方法签名上，比放在文档注释里可靠。
     *
     * <p>先查重再插入，这叫"先检查后执行"。严格来说它有并发漏洞：
     * 两个请求同时通过检查、然后都去插入。不过数据库上
     * {@code users.username} 有唯一约束，第二个插入会失败并回滚，
     * 所以最终数据仍然是对的——只是错误信息会变成一个数据库异常
     * 而不是友好的 409。<b>唯一约束才是真正的防线，应用层检查只是为了
     * 给出更好的错误提示。</b>
     *
     * @throws UsernameTakenException 用户名已被占用
     */
    @Transactional
    public User register(String username, String rawPassword) {
        if (userRepository.existsByUsername(username)) {
            throw new UsernameTakenException(username);
        }
        return userRepository.save(User.create(username, passwordEncoder.encode(rawPassword)));
    }

    /** 按用户名查询，查不到抛 404。用于 /api/auth/me 这类「必须有」的场景。 */
    @Transactional(readOnly = true)
    public User requireByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("用户不存在：" + username));
    }

    /**
     * 按 ID 查询。
     *
     * <p>返回 {@code Optional} 而不是抛异常，是因为调用场景不同：
     * 创建测试会话时「用户没登录」是完全正常的情况，
     * 不该用异常来表达——<b>异常应该留给"意料之外"，
     * 而不是"两种都正常的可能性之一"。</b>
     */
    @Transactional(readOnly = true)
    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }
}
