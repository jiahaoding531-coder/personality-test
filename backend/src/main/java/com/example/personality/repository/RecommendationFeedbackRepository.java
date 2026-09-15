package com.example.personality.repository;

import com.example.personality.entity.RecommendationFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RecommendationFeedbackRepository extends JpaRepository<RecommendationFeedback, Long> {

    Optional<RecommendationFeedback> findByRecommendationId(Long recommendationId);

    boolean existsByRecommendationId(Long recommendationId);

    /** 统计某次会话里被"喜欢"的推荐条数。用来算接受率。 */
    long countByRecommendationIdInAndReaction(
            java.util.Collection<Long> recommendationIds,
            RecommendationFeedback.Reaction reaction);
}
