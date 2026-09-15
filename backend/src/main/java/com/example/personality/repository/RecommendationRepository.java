package com.example.personality.repository;

import com.example.personality.entity.Recommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 推荐记录的数据访问接口。
 *
 * <p><b>⚠️ 这里刻意没有"按 sessionId 删除"的方法。</b>
 *
 * <p>推荐记录是 append-only 的：重新推荐要走"新开一批（batchNo + 1）"，
 * 而不是删掉旧的重插。因为 {@code recommendation_feedback} 外键挂在推荐行上，
 * 删推荐会级联删掉用户点过的反馈——那是项目里最该攒下来的数据。
 *
 * <p>所以别在这里加 {@code deleteBySessionId}。真需要清空，那是级联到
 * {@code test_sessions} 被删除时才该发生的事，数据库的 ON DELETE CASCADE 已经处理了。
 */
public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    /** 某个会话的<b>全部</b>推荐记录（含历史批次），按批次、名次升序。 */
    List<Recommendation> findBySessionIdOrderByBatchNoAscRankNoAsc(Long sessionId);

    /** 某个会话<b>某一批</b>的推荐，按名次升序（第 1 名在最前）。 */
    List<Recommendation> findBySessionIdAndBatchNoOrderByRankNoAsc(Long sessionId, int batchNo);

    /** 这个会话有没有推荐过。用于判断"重新推荐"还是"读缓存"。 */
    boolean existsBySessionId(Long sessionId);

    /**
     * 下一批的批次号：已有最大批次 + 1，没有记录时返回 1。
     *
     * <p>用 {@code coalesce} 兜住"一条记录都没有"的情况——没有它，
     * {@code max()} 返回 NULL，映射到 Java 的 int 会炸。
     *
     * <p>并发下两个请求可能算出同一个批次号，结果撞上
     * {@code uq_recommendations_rank} —— 这属于**期望行为**：
     * 唯一约束是最后一道防线，保证不会出现两批混在一起的数据。
     */
    @Query("select coalesce(max(r.batchNo), 0) + 1 from Recommendation r where r.sessionId = :sessionId")
    int nextBatchNo(@Param("sessionId") Long sessionId);
}
