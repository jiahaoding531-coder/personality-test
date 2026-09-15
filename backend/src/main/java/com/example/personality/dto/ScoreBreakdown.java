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
 * <h2>这五个数是相乘的关系</h2>
 *
 * <p>{@code score = interest × distance × quality × state × weather}。
 * 前端展示时应该把乘号画出来，而不是列成五行——<b>乘法意味着任何一项掉到 0
 * 整个结果就是 0</b>，这正是这个算法敢说"太远的地方不该被推荐"的原因
 * （加权求和会让"兴趣极高"补偿掉"距离极远"）。
 *
 * <p>全部是 0~1 的比例，前端自己乘 100 显示成百分比。
 *
 * <h2>⚠️ 判据是「有没有参与计算」，不是「是不是 1.0」</h2>
 *
 * <p>{@code distance} / {@code state} / {@code weather} 都会在"没有相应输入"
 * 时恒为 1.0，这时前端应当把它从乘积式里去掉——画一个「距离 100%」只会让
 * 用户以为"距离被考虑过、而且很合适"，而事实是<b>根本没定位、这一项没参与计算</b>。
 *
 * <p><b>但不能靠"值等于 1.0"来判断。</b>因为参与计算也可能恰好算出 1.0，
 * 而那种 1.0 是<b>有信息量的</b>：
 * <ul>
 *   <li>下雨天推一个室内博物馆 → 天气因子正好 1.0，
 *       含义是「今天下雨，但这个地方不受影响」——这恰恰是该说的</li>
 *   <li>用户说了"我累了"，推一个平地公园 → 状态因子接近 1.0，
 *       含义是「你累了，但这里不费腿」</li>
 * </ul>
 * <p>两种情况数值相同、含义天差地别。所以前端应该按<b>输入在不在</b>来取舍
 * （有没有定位、有没有状态、有没有天气），而不是看数值。
 * 后端已经把判断需要的原料都放在响应里了：{@code distanceKm}、
 * {@code appliedContext.states}、{@code appliedContext.weather}。
 *
 * @param interest 兴趣匹配度（主信号）
 * @param distance 距离衰减系数。没有定位时恒为 1.0
 * @param quality  质量修正。0.85~1.0，只往下扣
 * @param state    当前状态的修正。没有状态时恒为 1.0
 * @param weather  天气的修正。没有天气时恒为 1.0；晴天或地点全程室内时也是 1.0
 * @param finalScore 五个因子相乘的结果，也就是 {@code scorePercent / 100}
 */
public record ScoreBreakdown(
        double interest,
        double distance,
        double quality,
        double state,
        double weather,
        double finalScore
) {
}
