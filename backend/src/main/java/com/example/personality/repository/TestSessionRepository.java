package com.example.personality.repository;

import com.example.personality.entity.TestSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 测试会话的数据访问接口。
 *
 * <p>本接口没有声明任何方法，但已经可用了——{@link JpaRepository} 提供的基础方法
 * 就够 V0.1 用：
 * <ul>
 *   <li>{@code save(session)} —— 新增或更新（有 id 就是 UPDATE，没有就是 INSERT）</li>
 *   <li>{@code findById(id)} —— 返回 {@code Optional<TestSession>}</li>
 *   <li>{@code existsById(id)} —— 返回 boolean</li>
 * </ul>
 *
 * <p>这就是"约定优于配置"：你不需要为每个实体写一遍样板 CRUD。
 */
public interface TestSessionRepository extends JpaRepository<TestSession, Long> {

    /**
     * 某个用户的全部测试会话，按创建时间倒序（最近的在前）。
     *
     * <p>生成的 SQL 大致是：
     * <pre>
     *   SELECT * FROM test_sessions
     *   WHERE user_id = ?
     *   ORDER BY created_at DESC
     * </pre>
     *
     * <p>注意这里<b>不会</b>返回匿名会话（{@code user_id IS NULL} 的那些）——
     * {@code WHERE user_id = ?} 在 SQL 里天然排除 NULL，
     * 因为 {@code NULL = 任何值} 的结果是"未知"而不是 true。
     * 这正是我们想要的：历史记录只属于登录用户。
     */
    List<TestSession> findByUserIdOrderByCreatedAtDesc(Long userId);
}
