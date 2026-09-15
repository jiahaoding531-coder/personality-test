package com.example.personality.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 旅行偏好画像的响应。
 *
 * <h2>为什么维度只有 key / name / score 三个字段</h2>
 *
 * <p>人格画像的 {@link DimensionResult} 有 8 个字段（含 {@code level}、{@code levelLabel}、
 * {@code description}），这里刻意<b>不复用</b>它。原因是两个画像的形状差别很大：
 *
 * <ul>
 *   <li>人格量表每维度 4 道题，分数是连续的，三档（偏低/中等/偏高）有意义；
 *       旅行量表每维度只有 1 道题，分数<b>只能取 0/25/50/75/100 五档</b>，
 *       再套三档标签就基本是在重复分数本身</li>
 *   <li>8 个维度 × 3 个档位 = 24 段解读文案，而旅行测试的重点本来就在
 *       后面的 Top 3 推荐，不在画像解读上</li>
 * </ul>
 *
 * <p>代价是前端画图时不能直接复用按 {@code DimensionResult} 写死的组件——
 * 那个组件的 props 已经放宽成最小结构了（见 {@code BarChart}）。
 *
 * <p><b>⚠️ 字段名是前端契约，改动前先看 {@code docs/api.md}。</b>
 */
public record TravelProfileResponse(
        Long sessionId,
        String status,
        Instant createdAt,
        Instant submittedAt,
        // 固定是 "TRAVEL"。留着是为了将来前端能把两种画像混在一个列表里渲染。
        String scale,
        // 这份画像是不是从用户上一次测试借来的（当前会话还没测）
        boolean reused,
        List<TravelDimensionResult> dimensions
) {

    /**
     * 单个旅行维度的分数。
     *
     * @param key   维度英文标识（如 NATURE），前端用它做 key 和样式钩子
     * @param name  中文展示名（如「自然风光」）
     * @param score 0.00 ~ 100.00
     */
    public record TravelDimensionResult(String key, String name, BigDecimal score) {
    }
}
