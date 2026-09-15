package com.example.personality.dto;

import com.example.personality.entity.RecommendationFeedback;
import jakarta.validation.constraints.NotNull;

/**
 * 对一条推荐的反馈。
 *
 * <p>只有"喜欢/不喜欢"两种。计划书第六节还提到"换一个"和
 * "我累了""我饿了"这类自然语言反馈——那些不是这个接口的职责：
 * <ul>
 *   <li><b>换一个</b>不是反馈，是"再给我一批"——它对应的是再调一次推荐接口
 *       （带上 {@code excludeSeen}）</li>
 *   <li><b>自然语言状态</b>（累了、饿了）影响的是"当前处境"而不是"长期偏好"，
 *       将来接 AI 时作为 {@code RecommendationContext} 的输入，不该混进这张表</li>
 * </ul>
 *
 * @param reaction LIKE（喜欢）或 DISLIKE（不喜欢）。字段名和取值都必须与
 *                 {@code recommendation_feedback.reaction} 的 CHECK 约束一致
 */
public record FeedbackRequest(
        @NotNull(message = "reaction 不能为空，必须是 LIKE 或 DISLIKE")
        RecommendationFeedback.Reaction reaction
) {
}
