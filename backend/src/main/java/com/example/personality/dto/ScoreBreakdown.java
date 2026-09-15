package com.example.personality.dto;

/**
 * 一个推荐分数的构成——回答「**为什么是它**」。
 *
 * <h2>只给一个 62% 是回答不了这个问题的</h2>
 *
 * <p>同样是 62%，可能是完全不同的两种地方：
 * <ul>
 *   <li>"兴趣很合，但有点远"——兴趣 90% × 距离 69%</li>
 *   <li>"兴趣一般，但就在楼下"——兴趣 62% × 距离 100%</li>
 * </ul>
 * 对用户来说，这两个的决策含义是相反的。把因子摊开，他才知道该不该去。
 *
 * <h2>这四个数是相乘的关系</h2>
 *
 * <p>{@code score = interest × distance × quality × state}。
 * 前端展示时应该把乘号画出来，而不是列成四行——<b>乘法意味着任何一项掉到 0
 * 整个结果就是 0</b>，这正是这个算法敢说"太远的地方不该被推荐"的原因
 * （加权求和会让"兴趣极高"补偿掉"距离极远"）。
 *
 * <p>全部是 0~1 的比例，前端自己乘 100 显示成百分比。
 *
 * @param interest 兴趣匹配度（主信号）
 * @param distance 距离衰减系数。没有定位时恒为 1.0
 * @param quality  质量修正。0.85~1.0，只往下扣
 * @param state    当前状态的修正。没有状态时恒为 1.0
 * @param finalScore 四个因子相乘的结果，也就是 {@code scorePercent / 100}
 */
public record ScoreBreakdown(
        double interest,
        double distance,
        double quality,
        double state,
        double finalScore
) {
}
