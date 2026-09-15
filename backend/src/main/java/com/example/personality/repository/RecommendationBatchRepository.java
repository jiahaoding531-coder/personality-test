package com.example.personality.repository;

import com.example.personality.entity.RecommendationBatch;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
