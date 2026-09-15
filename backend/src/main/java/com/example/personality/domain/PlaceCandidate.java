package com.example.personality.domain;

import java.time.LocalTime;

/**
 * 参与推荐排序的一个候选地点。
 *
 * <p>这是「数据库实体」到「算法输入」之间的适配对象。为什么不直接用
 * {@code entity.Place}？和 {@code ScoredItem} 是同一个理由：
 * <b>算法只该依赖它真正需要的东西</b>。
 *
 * <p>用实体的话，{@code RecommendationEngine} 就得依赖 JPA——
 * 想测一个纯数学问题，却要先把 Spring 和数据库拉起来。
 * 有了这层隔离，引擎的单元测试是毫秒级的。
 */
public record PlaceCandidate(
        Long id,
        String name,
        String category,
        double latitude,
        double longitude,
        PlaceTraits traits,
        /** 综合质量分 0~100。和用户偏好无关，是这个地方本身好不好。 */
        int quality,
        /** 门票价格（元）。V0 只记录不参与计算，留给后续做预算筛选。 */
        int ticketPrice,
        /** 建议停留时长（分钟）。 */
        int suggestedMinutes,
        /** 营业开始时间。null 表示全天开放（公园、街区）。 */
        LocalTime openFrom,
        /** 营业结束时间。null 表示全天开放。 */
        LocalTime openTo,
        String description
) {

    /**
     * 在给定时刻是否营业。
     *
     * <p>只处理**同一天内**的营业时段。跨午夜的场所（比如夜市
     * {@code 17:30~02:00}）会被当成"到 24:00 就关门"——
     * 这在 V0 是可接受的简化，因为凌晨 2 点还在外面逛的场景
     * 不在当前的使用范围里。真要做对，应该把营业时段建模成
     * 一组时间段而不是两个时刻。
     */
    public boolean isOpenAt(LocalTime time) {
        if (openFrom == null || openTo == null) {
            return true;   // 全天开放
        }
        return !time.isBefore(openFrom) && !time.isAfter(openTo);
    }
}
