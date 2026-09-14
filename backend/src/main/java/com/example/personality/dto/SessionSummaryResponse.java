package com.example.personality.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 历史列表里的一条测试记录。
 *
 * <p><b>为什么单独建一个 DTO，而不是直接复用 {@code SessionResultResponse}？</b>
 *
 * <p>因为两者的用途不同：结果页只需要展示**一次**测试，可以带上全部细节
 * （每段几百字的解读文案、免责声明、原始分等）；而历史列表要展示**几十次**，
 * 如果每条都带上那些字段，响应体会膨胀几十倍，而前端一个字都用不上。
 *
 * <p>这体现了 DTO 的另一个价值（除了"别暴露实体"之外）：
 * <b>按使用场景裁剪数据结构</b>。同一个"会话"，在结果页和历史页
 * 需要的信息量是完全不同的。
 *
 * @param status     会话状态。可能为 IN_PROGRESS（答到一半就关页面了），
 *                   这时 {@code dimensions} 是空列表
 * @param dimensions 5 个维度的简要分数。**不含解读文案**——列表页不展示它
 */
public record SessionSummaryResponse(
        Long sessionId,
        Instant createdAt,
        Instant submittedAt,
        String status,
        List<DimensionBrief> dimensions
) {

    /** 单个维度的简要信息，只有画缩略图需要的字段。 */
    public record DimensionBrief(
            String key,
            String name,
            BigDecimal score,
            String levelLabel
    ) {
    }
}
