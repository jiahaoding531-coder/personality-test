package com.example.personality.domain;

import java.util.List;

/**
 * 一个地点的推荐结果。
 *
 * <h2>五个因子全都留下来了</h2>
 *
 * <p>{@code score = interestScore × distanceFactor × qualityFactor × stateFactor
 * × weatherFactor}。
 * 把它们分别暴露出来，是为了能回答「<b>为什么是它，而不是别人</b>」——
 * 只给一个 62% 是回答不了这个问题的：62% 可能是"兴趣很合但有点远"，
 * 也可能是"兴趣一般但就在楼下"，两者的决策含义完全相反。
 *
 * <p>这同时也是将来交给 AI 生成自然语言推荐理由的输入：
 * 没有这份结构化的拆解，AI 只能编。
 *
 * @param place           地点本身
 * @param score           最终得分，0~1，越高越推荐
 * @param interestScore   兴趣匹配度，0~1。<b>这是四个因子里的主信号</b>
 * @param distanceKm      距离（公里）。没有定位时为 null
 * @param distanceFactor  距离衰减系数，0~1。没有定位时恒为 1.0
 * @param qualityFactor   质量修正系数，0.85~1.0。只往下扣，不往上加
 * @param stateFactor     当前状态的修正系数，0~1。没有状态时恒为 1.0
 * @param weatherFactor   天气的修正系数，0~1。<b>没拿到天气时恒为 1.0</b>；
 *                        天气不影响时（晴天、或全程室内）也是 1.0
 * @param topMatches      贡献最大的几个维度，用来解释"为什么推荐这个"
 */
public record ScoredPlace(
        PlaceCandidate place,
        double score,
        double interestScore,
        Double distanceKm,
        double distanceFactor,
        double qualityFactor,
        double stateFactor,
        double weatherFactor,
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
