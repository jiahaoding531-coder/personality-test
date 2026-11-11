package com.example.personality.experiment;

import com.example.personality.domain.ScoredPlace;
import java.util.*;

/** 离线排序契约；不参与 Spring 装配。 */
public final class OfflineRanking {
    public enum Strategy { DISTANCE, RELEVANCE, FAIR_EXPOSURE }
    public static final int TOP_K = 3;
    public static final String VERSION = "offline-three-baselines-v1";

    public static List<ScoredPlace> rank(List<ScoredPlace> candidates, Strategy strategy,
                                         Map<Long, Long> exposures, double weight) {
        if (candidates == null || strategy == null || exposures == null
                || !Double.isFinite(weight) || weight < 0 || weight > 1) {
            throw new IllegalArgumentException("排序输入或公平权重非法");
        }
        for (var entry : exposures.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0) {
                throw new IllegalArgumentException("曝光计数不能为负数或空值");
            }
        }
        Set<Long> ids = new HashSet<>();
        for (var candidate : candidates) {
            if (candidate == null || candidate.place() == null || candidate.place().id() == null
                    || !ids.add(candidate.place().id()) || !Double.isFinite(candidate.score())
                    || candidate.score() < 0 || candidate.score() > 1
                    || candidate.distanceKm() == null || !Double.isFinite(candidate.distanceKm())
                    || candidate.distanceKm() < 0) {
                throw new IllegalArgumentException("候选 ID、评分或直线距离非法");
            }
        }
        Comparator<ScoredPlace> byScore = Comparator.comparingDouble(ScoredPlace::score).reversed();
        Comparator<ScoredPlace> order = switch (strategy) {
            case DISTANCE -> Comparator.comparingDouble(ScoredPlace::distanceKm);
            case RELEVANCE -> byScore;
            case FAIR_EXPOSURE -> Comparator.<ScoredPlace>comparingDouble(
                    p -> priority(p, strategy, exposures, weight)).reversed().thenComparing(byScore);
        };
        // 排序副本；不改原分、不改原始候选，也不在试排时累计曝光。
        return candidates.stream().sorted(order.thenComparingLong(p -> p.place().id())).limit(TOP_K).toList();
    }

    public static double priority(ScoredPlace candidate, Strategy strategy, Map<Long, Long> exposures, double weight) {
        return switch (strategy) {
            case DISTANCE -> -candidate.distanceKm();
            case RELEVANCE -> candidate.score();
            case FAIR_EXPOSURE -> (1 - weight) * candidate.score()
                    + weight / (1.0 + exposures.getOrDefault(candidate.place().id(), 0L));
        };
    }
}
