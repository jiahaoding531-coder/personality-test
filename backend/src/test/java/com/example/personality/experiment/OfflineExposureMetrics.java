package com.example.personality.experiment;

import java.util.*;

public final class OfflineExposureMetrics {
    public record Metrics(int eligibleNodes, long selections, Double gini, Double longTailShare,
                          Double coverage, Double zeroExposureShare, Double opportunityAdjustedGini) { }

    public static Metrics calculate(Map<Long, Long> exposures, Map<Long, Integer> opportunities,
                                    Set<Long> longTail) {
        Objects.requireNonNull(exposures);
        Objects.requireNonNull(opportunities);
        Objects.requireNonNull(longTail);
        for (var entry : opportunities.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0) {
                throw new IllegalArgumentException("可行机会次数非法");
            }
        }
        for (var entry : exposures.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0
                    || entry.getValue() > opportunities.getOrDefault(entry.getKey(), 0)) {
                throw new IllegalArgumentException("入选次数必须介于零与可行机会次数之间");
            }
        }
        List<Double> counts = new ArrayList<>();
        List<Double> adjusted = new ArrayList<>();
        long total = 0, tail = 0;
        int covered = 0;
        // 只用可行机会决定分母，不能根据“有没有入选”挑选节点。
        for (var entry : new TreeMap<>(opportunities).entrySet()) {
            if (entry.getValue() == 0) continue;
            long count = exposures.getOrDefault(entry.getKey(), 0L);
            counts.add((double) count);
            adjusted.add(count / (double) entry.getValue());
            total += count;
            if (longTail.contains(entry.getKey())) tail += count;
            if (count > 0) covered++;
        }
        int size = counts.size();
        return new Metrics(size, total, gini(counts), total == 0 ? null : tail / (double) total,
                size == 0 ? null : covered / (double) size,
                size == 0 ? null : (size - covered) / (double) size, gini(adjusted));
    }

    private static Double gini(List<Double> values) {
        double total = values.stream().mapToDouble(Double::doubleValue).sum();
        if (values.isEmpty() || total == 0) return null;
        values.sort(Double::compareTo);
        double weighted = 0;
        for (int i = 0; i < values.size(); i++) weighted += (2.0 * (i + 1) - values.size() - 1) * values.get(i);
        return weighted / (values.size() * total);
    }
}
