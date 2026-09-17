package com.example.personality.repository;

import com.example.personality.entity.RecommendationBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 推荐批次处境的数据访问接口。
 *
 * <p>和 {@link RecommendationRepository} 一样，这里<b>不提供按会话删除的方法</b>——
 * 批次和推荐是一起 append-only 的，真要清空是级联到 {@code test_sessions}
 * 被删除时才发生的事，数据库的 {@code ON DELETE CASCADE} 已经处理了。
 */
public interface RecommendationBatchRepository extends JpaRepository<RecommendationBatch, Long> {

    /** 某个会话某一批的处境。 */
    Optional<RecommendationBatch> findBySessionIdAndBatchNo(Long sessionId, int batchNo);

    /**
     * 某个会话<b>最新一批</b>的处境。
     *
     * <p>生成 AI 理由时用它：前端展示的就是最新那一批，理由自然也针对那一批。
     *
     * <p>用 {@code OrderByBatchNoDesc} + {@code findFirst} 而不是写 SQL 取 max：
     * 批次号是递增的，倒序第一条就是最新的，语义直白且用得上
     * {@code (session_id, batch_no)} 上的索引。
     */
    Optional<RecommendationBatch> findFirstBySessionIdOrderByBatchNoDesc(Long sessionId);

    /**
     * 下一批的批次号：已有最大批次 + 1，没有记录时返回 1。
     *
     * <p>正常情况下它应当和 {@link RecommendationRepository#nextBatchNo(Long)}
     * 算出同一个值。这里仍然单独查询，是为了兼容 V11 缺陷留下的脏数据：
     * 当时空结果也会写 {@code recommendation_batches}，却不会写
     * {@code recommendations}。只看推荐表会重复使用已经占掉的批次号，
     * 最终撞上唯一约束并返回 500。
     */
    @Query("select coalesce(max(b.batchNo), 0) + 1 from RecommendationBatch b "
            + "where b.sessionId = :sessionId")
    int nextBatchNo(@Param("sessionId") Long sessionId);
}
