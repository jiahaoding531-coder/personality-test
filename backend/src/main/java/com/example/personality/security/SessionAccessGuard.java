package com.example.personality.security;

import com.example.personality.entity.TestSession;
import com.example.personality.exception.ResourceNotFoundException;
import com.example.personality.repository.TestSessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 测试会话的访问控制。
 *
 * <h2>它防的是什么</h2>
 *
 * <p>为了让「不登录也能做测试」，会话相关的端点必须匿名可访问。
 * 但如果只靠 sessionId 判断所有权，就会有严重的越权问题——
 * <b>因为 id 是数据库自增的连续整数，从 1 数到 N 就能拿到全站数据。</b>
 *
 * <p>这是 IDOR（不安全的直接对象引用，OWASP A01）。修复前的实测结果：
 * <ul>
 *   <li>匿名 {@code GET /api/test-sessions/24/result} → 200，读到别人的画像</li>
 *   <li>匿名 {@code POST /api/test-sessions/22/answers} → 200，写进别人未提交的会话</li>
 * </ul>
 *
 * <h2>放行规则（满足其一即可）</h2>
 *
 * <ol>
 *   <li><b>令牌匹配</b>——创建会话时发的随机 UUID。
 *       这是匿名用户的凭证：持有令牌 = 拥有该会话。</li>
 *   <li><b>是登录用户本人的会话</b>——从会话里拿到的 userId 和当前身份一致。
 *       这样登录用户查自己的历史不需要额外带令牌。</li>
 * </ol>
 *
 * <h2>为什么拒绝时返回 404 而不是 403</h2>
 *
 * <p>403 的意思是「这个资源存在，但你没权限」——等于告诉攻击者
 * <b>哪些 id 是真实存在的</b>。虽然拿到了 id 也进不去，但这本身就是
 * 信息泄露：攻击者可以据此估算系统规模、找到有效的攻击目标。
 *
 * <p>404 则让「不存在」和「没权限」看起来完全一样，攻击者无法区分。
 * 这也是 GitHub 等平台对私有仓库的做法（返回 404 而不是 403）。
 */
@Component
public class SessionAccessGuard {

    private final TestSessionRepository sessionRepository;

    public SessionAccessGuard(TestSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * 加载会话并校验访问权限，不通过就抛 404。
     *
     * @param sessionId 路径里的会话 id
     * @param token     请求头 {@code X-Session-Token} 里的令牌，可以为 null
     * @param principal 当前登录用户，未登录时为 null
     * @return 通过校验的会话实体
     * @throws ResourceNotFoundException 会话不存在，或无权访问（刻意不区分）
     */
    @Transactional(readOnly = true)
    public TestSession requireAccess(Long sessionId, UUID token, AppUserPrincipal principal) {
        TestSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> notFound(sessionId));

        // 规则 1：令牌匹配。匿名用户靠这个。
        if (token != null && session.matchesToken(token)) {
            return session;
        }

        // 规则 2：登录用户访问自己的会话。
        // 注意要判 session.getUserId() 非空——匿名会话的 userId 是 null，
        // 不能因为 principal 非空就放行（否则任何登录用户都能看所有匿名会话）。
        if (principal != null
                && session.getUserId() != null
                && session.getUserId().equals(principal.getId())) {
            return session;
        }

        // 两条都不满足：拒绝。
        throw notFound(sessionId);
    }

    /**
     * 把请求头里的字符串解析成 UUID；空值或格式非法一律返回 null。
     *
     * <p><b>为什么不直接让 Spring 把请求头绑定成 UUID 类型？</b>
     * 那样格式非法会抛 {@code MethodArgumentTypeMismatchException}，
     * 被全局处理器转成 400。于是攻击者能观察到：
     * 「格式对的令牌 → 404」而「格式错的令牌 → 400」——
     * 这个小差异本身就泄露了信息。
     *
     * <p>统一返回 null 之后，各种非法输入和「没带令牌」走的是同一条路径，
     * 最终都是 404，外部观察不出区别。
     */
    public static UUID parseToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 构造「不存在」异常。
     *
     * <p>消息里只有 id，没有「无权访问」之类的字眼——从响应上
     * 完全看不出这个会话到底存不存在。
     */
    private static ResourceNotFoundException notFound(Long sessionId) {
        return new ResourceNotFoundException("测试会话不存在：id=" + sessionId);
    }
}
