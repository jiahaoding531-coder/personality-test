package com.example.personality.repository;

import com.example.personality.entity.PersonalityProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 人格画像的数据访问接口。
 */
public interface PersonalityProfileRepository extends JpaRepository<PersonalityProfile, Long> {

    /**
     * 按会话 ID 查画像。
     *
     * <p><b>为什么返回 {@code Optional<PersonalityProfile>} 而不是
     * {@code PersonalityProfile}（可能为 null）？</b>
     *
     * <p>因为方法签名本身就在告诉调用者"这个查询可能查不到"。你没法忘记处理
     * 空值——拿到 Optional 之后，编译器逼着你调用 {@code .orElseThrow(...)} 或
     * {@code .orElse(...)} 才能拿到真正的对象。
     *
     * <p>对比一下：如果返回可空的 PersonalityProfile，以下代码编译完全通过，
     * 运行到线上才抛 NullPointerException：
     * <pre>
     *   PersonalityProfile p = repo.findBySessionId(id);  // 可能返回 null
     *   return p.getOpenness();                            // 💥 NPE
     * </pre>
     *
     * <p>{@code Optional} 是 Java 8 引入的，用"类型系统"来消灭一类 bug。
     * 后面 TestSessionService 里会看到它的实际用法。
     */
    Optional<PersonalityProfile> findBySessionId(Long sessionId);

    /** 判断某会话是否已经算过画像。用于 submit 的幂等性检查。 */
    boolean existsBySessionId(Long sessionId);

    /**
     * <b>一次性</b>取出多个会话的画像。
     *
     * <p>这个方法专门用来避免 <b>N+1 查询</b>——后端最常见的性能问题之一。
     *
     * <p>假设历史记录页要展示 20 次测试的分数：
     * <pre>
     *   // ❌ N+1：1 次查会话列表 + 20 次查画像 = 21 次查询
     *   for (TestSession s : sessions) {
     *       profileRepository.findBySessionId(s.getId());   // 每次都是一轮数据库往返
     *   }
     *
     *   // ✅ 2 次查询，无论多少条记录
     *   List&lt;Long&gt; ids = sessions.stream().map(TestSession::getId).toList();
     *   Map&lt;Long, PersonalityProfile&gt; bySession =
     *       profileRepository.findBySessionIdIn(ids).stream()
     *           .collect(toMap(PersonalityProfile::getSessionId, identity()));
     * </pre>
     *
     * <p>"N+1"这个名字就来自这个模式：1 次列表查询 + N 次明细查询。
     * 数据量小的时候看不出来，一旦 N 变成几百，接口就会慢到无法接受。
     *
     * <p>生成为 {@code WHERE session_id IN (?, ?, ...)}。
     * 参数用 {@code Collection} 而不是 {@code List}，因为它只需要能遍历和判空。
     */
    List<PersonalityProfile> findBySessionIdIn(Collection<Long> sessionIds);
}
