package com.example.personality.service;

/** 短事务读取出的推荐目标事实快照。 */
public record FeedbackTargetContext(
        Long recommendationId,
        String placeName,
        Double distanceMeters,
        Integer suggestedCloserDistanceMeters,
        Integer ticketPrice,
        Integer suggestedBudgetMax) {
}
