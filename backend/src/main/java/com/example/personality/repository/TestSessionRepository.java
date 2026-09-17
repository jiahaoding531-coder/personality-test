package com.example.personality.repository;

import com.example.personality.entity.QuestionScale;
import com.example.personality.entity.TestSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

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
     * 锁住单个会话，直到当前事务提交。
     *
     * <p>它相当于 JDBC 的 {@code SELECT ... FOR UPDATE}：同一会话的并发推荐会排队，
     * 不同会话互不影响。这样“读最大批次 + 1 + 插入”才能作为一个原子步骤执行。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TestSession s where s.id = :id")
    Optional<TestSession> findByIdForUpdate(@Param("id") Long id);

    /**
     * 某个用户在<b>指定量表</b>下的全部测试会话，按创建时间倒序（最近的在前）。
     *
     * <p>生成的 SQL 大致是：
     * <pre>
     *   SELECT * FROM test_sessions
     *   WHERE user_id = ? AND scale = ?
     *   ORDER BY created_at DESC
     * </pre>
     *
     * <p>注意这里<b>不会</b>返回匿名会话（{@code user_id IS NULL} 的那些）——
     * {@code WHERE user_id = ?} 在 SQL 里天然排除 NULL，
     * 因为 {@code NULL = 任何值} 的结果是"未知"而不是 true。
     * 这正是我们想要的：历史记录只属于登录用户。
     *
     * <p><b>⚠️ 为什么必须带 scale，不能只按 user_id 查。</b>
     *
     * <p>{@code test_sessions} 表从 V8 起同时装着两种会话。历史列表的契约是
     * "人格测试记录"（返回的是 5 个人格维度的分数），但旅行会话
     * <b>在 personality_profiles 里没有对应记录</b>，查出来画像为空，
     * 前端就会把一个已经提交过的旅行测试显示成「未完成」，
     * 点进去还会因为人格画像不存在而报 404。
     *
     * <p>所以这里刻意<b>不提供</b>"只按 user_id 查"的方法——不然下次
     * 又会有人不小心把两种会话一起捞出来。和 {@code QuestionRepository}
     * 里不提供"取全部题目"是同一个道理。
     */
    List<TestSession> findByUserIdAndScaleOrderByCreatedAtDesc(Long userId, QuestionScale scale);
}
