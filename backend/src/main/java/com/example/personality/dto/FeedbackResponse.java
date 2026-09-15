package com.example.personality.dto;

import java.util.List;

/**
 * 提交反馈后的响应——告诉前端"这次反馈让画像发生了什么变化"。
 *
 * <h2>为什么要返回调整结果</h2>
 *
 * <p>反馈功能最容易失败的地方是<b>用户点完没有任何感觉</b>。
 * 画像是"下次推荐时会用到"的东西，用户当场看不到任何变化，
 * 很容易以为按钮是坏的、或者以为自己的点击没被记录。
 *
 * <p>所以这里把调整前后的分数直接返回，前端可以显示成
 * 「自然风光 75 → 65（根据你的反馈）」，让反馈的影响立刻可见。
 *
 * <h2>⚠️ 注意这些调整不写回 {@code travel_profiles}</h2>
 *
 * <p>问卷画像永远保持原样，调整是每次推荐时实时算出来的。
 * 理由见 {@code TravelPreferenceAdjuster} 的类注释：
 * 问卷结果和反馈修正混成一份之后，"问卷准不准"就再也分析不了了。
 *
 * @param recommendationId 被反馈的那条推荐
 * @param reaction         这次记下的反应（LIKE / DISLIKE）
 * @param adjustments      被反馈影响到的维度。<b>没被影响到的维度不出现</b>；
 *                         一条反馈都没产生净影响时是空列表
 */
public record FeedbackResponse(
        Long recommendationId,
        String reaction,
        List<Adjustment> adjustments
) {

    /**
     * 单个维度的调整明细。
     *
     * @param key                维度英文标识（如 NATURE）
     * @param name               中文名
     * @param questionnaireScore 问卷算出来的原始分（0~100）
     * @param effectiveScore     叠加反馈修正后、推荐实际使用的分数（0~100）
     */
    public record Adjustment(
            String key,
            String name,
            int questionnaireScore,
            int effectiveScore
    ) {
    }
}
