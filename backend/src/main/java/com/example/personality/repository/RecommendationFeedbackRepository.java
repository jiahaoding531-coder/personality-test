package com.example.personality.repository;

import com.example.personality.entity.RecommendationFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RecommendationFeedbackRepository extends JpaRepository<RecommendationFeedback, Long> {

    Optional<RecommendationFeedback> findByRecommendationId(Long recommendationId);

    boolean existsByRecommendationId(Long recommendationId);

    /** 统计某次会话里被"喜欢"的推荐条数。用来算接受率。 */
    long countByRecommendationIdInAndReaction(
            Collection<Long> recommendationIds,
            RecommendationFeedback.Reaction reaction);

    /**
     * 一次取出多条推荐上的反馈。
     *
     * <p>算"这个会话收到过哪些反馈"时用它，而不是逐条推荐去查——
     * 那会变成 N+1 查询（和 {@code HistoryService} 里刻意避免的是同一类问题）。
     */
    List<RecommendationFeedback> findByRecommendationIdIn(Collection<Long> recommendationIds);
}
