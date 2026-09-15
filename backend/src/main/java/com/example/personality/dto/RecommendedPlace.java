package com.example.personality.dto;

import java.util.List;

/**
 * 一个被推荐的地点。
 *
 * <p>字段分成三组，对应"用户做决定时要看什么"：
 * <ul>
 *   <li><b>这是什么</b>：name / category / latitude / longitude / description</li>
 *   <li><b>为什么推荐它</b>：scorePercent / reasons</li>
 *   <li><b>去得了吗</b>：distanceKm / ticketPrice / suggestedMinutes / openFrom / openTo</li>
 * </ul>
 *
 * <p>分数同时给了 {@code scorePercent}（整数百分比）而没有给原始的 0~1 小数，
 * 是因为这个值最终是给人看的。要排序、要比较时前端用它也够了——
 * 真正的排序在后端已经做完了，这里返回的列表本来就是有序的。
 *
 * @param rank             名次，从 1 开始
 * @param recommendationId 这条推荐记录的 ID。
 *                         <b>点 👍/👎 时要带上它</b>——反馈是挂在"某一次推荐的某一条"上的，
 *                         不是挂在地点上（同一个地点在不同批次里是不同的推荐）
 * @param placeId          地点 ID
 * @param name             地点名
 * @param category         类别（NATURE / CULTURE / FOOD / PHOTO / DISTRICT / MUSEUM / MARKET）
 * @param latitude         地点纬度。<b>用来拼"导航过去"的链接</b>——
 *                         没有它前端只能显示一个地名，用户还得自己去地图里搜
 * @param longitude        地点经度
 * @param description      一句话介绍
 * @param scorePercent     综合得分，0~100 的整数（兴趣匹配 × 距离衰减 × 质量修正 × 状态修正 × 天气修正）
 * @param scoreBreakdown   这个分数的构成。只给一个百分数是回答不了"为什么是它"的——
 *                         同样是 62%，"兴趣很合但有点远"和"兴趣一般但就在楼下"
 *                         对用户的决策含义完全相反
 * @param distanceKm       距离用户的公里数。用户没给定位时为 null（但接口上定位是必填的，正常不会为 null）
 * @param ticketPrice      门票价格（元），0 表示免费
 * @param suggestedMinutes 建议停留时长（分钟）
 * @param openFrom         开始营业时间（"HH:mm"）。<b>null 表示全天开放</b>，比如公园和街区
 * @param openTo           结束营业时间（"HH:mm"）。null 表示全天开放
 * @param reasons          为什么推荐它，按贡献从大到小，最多 3 条
 */
public record RecommendedPlace(
        int rank,
        Long recommendationId,
        Long placeId,
        String name,
        String category,
        double latitude,
        double longitude,
        String description,
        int scorePercent,
        ScoreBreakdown scoreBreakdown,
        Double distanceKm,
        int ticketPrice,
        int suggestedMinutes,
        String openFrom,
        String openTo,
        List<MatchReason> reasons
) {
}
