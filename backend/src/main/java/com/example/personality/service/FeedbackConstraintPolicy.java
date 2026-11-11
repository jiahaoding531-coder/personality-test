package com.example.personality.service;

import java.util.Optional;

/** 根据服务端已掌握的地点事实，确定性地生成收紧约束。 */
public final class FeedbackConstraintPolicy {

    private FeedbackConstraintPolicy() {
    }

    /**
     * 为“太远”生成不放宽当前范围的新距离上限。
     * 距离不足 700 米时不提供建议，避免产生低于接口下限的值。
     */
    public static Optional<Integer> suggestCloserLimit(double distanceMeters,
                                                        Integer currentMaxDistanceMeters) {
        if (!Double.isFinite(distanceMeters) || distanceMeters < 700) {
            return Optional.empty();
        }
        int suggested = Math.min(50_000, Math.max(500,
                (int) (Math.floor(distanceMeters * 0.75 / 100.0) * 100)));
        if (currentMaxDistanceMeters != null) {
            suggested = Math.min(suggested, currentMaxDistanceMeters);
        }
        return Optional.of(suggested);
    }

    /**
     * 为“太贵”生成比目标票价低一个十元档的预算上限。
     * 免费地点或没有票价事实时不生成建议。
     */
    public static Optional<Integer> suggestBudgetLimit(Integer ticketPrice,
                                                        Integer currentBudgetMax) {
        if (ticketPrice == null || ticketPrice <= 0) {
            return Optional.empty();
        }
        int suggested = Math.max(0, ((ticketPrice - 1) / 10) * 10);
        if (currentBudgetMax != null) {
            suggested = Math.min(suggested, currentBudgetMax);
        }
        return Optional.of(suggested);
    }

    /** 计算两点之间的 Haversine 球面距离，单位为米。 */
    public static double haversineMeters(double latitude1, double longitude1,
                                         double latitude2, double longitude2) {
        double earthRadiusMeters = 6_371_000.0;
        double lat1 = Math.toRadians(latitude1);
        double lat2 = Math.toRadians(latitude2);
        double deltaLat = Math.toRadians(latitude2 - latitude1);
        double deltaLon = Math.toRadians(longitude2 - longitude1);
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        return earthRadiusMeters * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
