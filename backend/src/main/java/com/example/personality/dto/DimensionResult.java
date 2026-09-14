package com.example.personality.dto;

import java.math.BigDecimal;

/**
 * 结果页上单个维度的完整信息。
 *
 * <p><b>这个 record 的字段名是前端契约，V0.2 不要随意改名。</b>
 * React 那边会直接按 {@code key} 做图表分组、按 {@code name} 做图例、
 * 按 {@code score} 画雷达图。
 *
 * @param key         维度的英文标识，如 "OPENNESS"。前端用它做 Map 的 key，不用来展示
 * @param name        中文名，如 "开放性"。给用户看的
 * @param score       归一化后的分数，0.00 ~ 100.00
 * @param rawScore    该维度有效分之和（反向题已翻转）。同时给出来是为了让结果可追溯——
 *                    用户看到 87.50 分不服气时，能知道是 4 道题拿了 18 分算出来的
 * @param itemCount   该维度共几道题
 * @param level       档位的英文标识：LOW / MEDIUM / HIGH
 * @param levelLabel  档位中文：偏低 / 中等 / 偏高
 * @param description 这个档位下的中文解读文案
 */
public record DimensionResult(
        String key,
        String name,
        BigDecimal score,
        int rawScore,
        int itemCount,
        String level,
        String levelLabel,
        String description
) {
}
