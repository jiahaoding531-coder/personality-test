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
        /**
         * 室内程度 0~100。<b>0 = 完全户外，100 = 全程室内。</b>
         *
         * <p>天气参与打分时用它决定"下雨天该不该降权"：越靠户外，
         * 恶劣天气扣得越狠；全程室内的地点完全不受影响。
         *
         * <p>⚠️ 它<b>不在 {@link PlaceTraits} 里</b>，是刻意分开的。
         * {@code PlaceTraits} 是按 {@link TravelDimension} 索引的——
         * 那 8 个维度是"用户偏好"，每个都对应一道题。而"室内还是户外"
         * 是地点的客观属性，<b>用户没有、也不该有"室内偏好"这一维度</b>
         * （问"你多喜欢室内"没有意义，那取决于今天下不下雨）。
         * 塞进去会破坏那个一一对应的关系。
         */
        int indoor,
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
