package com.example.personality.domain;

import java.time.LocalTime;

/**
 * 做一次推荐的「当前处境」。
 *
 * <p>计划书第十二节列了推荐分数要考虑的因素，其中这几个属于"此刻的状态"
 * 而不是"用户是谁"——它们每次请求都可能不同，所以单独打包传进来。
 *
 * <p>V0 只实现了**时间**相关的部分（现在几点、还剩多少时间）。
 * 天气和定位留了字段但没接数据源，等接高德 API 时再填。
 *
 * @param now              当前时间（用户所在时区）。用来判断地点开没开门。
 * @param remainingMinutes 今天还剩多少可以玩的时间。
 *                         用户说"我有 3 小时"，一个需要 3.5 小时的地方就不该推荐。
 * @param latitude         用户当前位置纬度。null 表示没有定位。
 * @param longitude        用户当前位置经度。
 * @param maxDistanceKm    候选地点的最大半径。默认 10 公里。
 */
public record RecommendationContext(
        LocalTime now,
        int remainingMinutes,
        Double latitude,
        Double longitude,
        double maxDistanceKm
) {

    public static final double DEFAULT_MAX_DISTANCE_KM = 10.0;

    /** 最常用的场景：知道现在几点、还有多少时间，但没定位。 */
    public static RecommendationContext of(LocalTime now, int remainingMinutes) {
        return new RecommendationContext(now, remainingMinutes, null, null, DEFAULT_MAX_DISTANCE_KM);
    }

    /** 有没有定位。没有的话距离因素会失效（所有地点距离分一样）。 */
    public boolean hasLocation() {
        return latitude != null && longitude != null;
    }
}
