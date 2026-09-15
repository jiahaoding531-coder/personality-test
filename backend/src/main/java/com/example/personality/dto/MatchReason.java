package com.example.personality.dto;

/**
 * 一条"为什么推荐它"的依据：某个维度上，你的偏好和这个地方的属性都对上了。
 *
 * <p>前端可以据此渲染成一句话，比如：
 * <blockquote>你很在乎「自然风光」（95），这里 98 分</blockquote>
 *
 * <h2>为什么这里是"结构化依据"而不是一句自然语言</h2>
 *
 * <p>计划书第七节的产品原则写得很明确：
 * <blockquote>AI 负责理解、总结和解释；传统算法负责距离、时间、营业状态和数值排序。</blockquote>
 *
 * <p>这几个数字就是"传统算法"的产出——完全确定、可测试、不花一分钱。
 * 把 {@code userPreference} 和 {@code placeValue} 都给出去，而不是只给一个
 * 算好的贡献值，是因为"你打了 95 分"和"这个地方 98 分"对用户是两条不同的信息：
 * 前者说明你确实在乎，后者说明它确实满足。
 *
 * <p>将来接 AI 生成推荐理由时，这个结构正好是喂给大模型的输入——
 * 那时候 {@code recommendations.reason} 那一列才会被填上（V9 里预留了）。
 *
 * @param dimensionKey    维度英文标识（如 NATURE）
 * @param dimensionLabel  维度中文名（如「自然风光」）
 * @param userPreference  用户在**这次会话的画像**里给这个维度打的分（0~100）
 * @param placeValue      这个地点在该属性上的得分（0~100）
 */
public record MatchReason(
        String dimensionKey,
        String dimensionLabel,
        int userPreference,
        int placeValue
) {
}
