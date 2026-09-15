package com.example.personality.domain;

import java.util.List;

/**
 * 一个地点的推荐结果。
 *
 * @param place           地点本身
 * @param score           最终得分，0~1，越高越推荐
 * @param interestScore   兴趣匹配度，0~1。<b>这是三个因子里的主信号</b>
 * @param distanceKm      距离（公里）。没有定位时为 null
 * @param qualityFactor   质量修正系数（接近 1 的倍数，只做微调）
 * @param topMatches      贡献最大的几个维度，用来解释"为什么推荐这个"
 */
public record ScoredPlace(
        PlaceCandidate place,
        double score,
        double interestScore,
        Double distanceKm,
        double qualityFactor,
        List<MatchedDimension> topMatches
) {

    /** 转化为百分制，给前端展示用。 */
    public int scorePercent() {
        return (int) Math.round(score * 100);
    }

    /**
     * 单个维度的匹配情况。
     *
     * @param dimension      维度
     * @param userPreference 用户在这个维度上的偏好值 0~100
     * @param placeValue     地点在这个维度上的属性值 0~100
     * @param contribution   这个维度对总分贡献了多少（已经加权）
     */
    public record MatchedDimension(
            TravelDimension dimension,
            int userPreference,
            int placeValue,
            double contribution
    ) {
    }
}
