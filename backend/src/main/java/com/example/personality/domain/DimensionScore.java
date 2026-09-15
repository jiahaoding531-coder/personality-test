package com.example.personality.domain;

import java.math.BigDecimal;

/**
 * 单个维度的计分结果。
 *
 * <p>类型参数 {@code D} 的含义见 {@link ScoredItem}——它跟着输入走：
 * 人格会话得到 {@code DimensionScore<Dimension>}，
 * 旅行会话得到 {@code DimensionScore<TravelDimension>}。
 *
 * @param dimension  维度
 * @param rawSum     该维度所有题目的<b>有效分</b>之和（反向题已经翻转过了），范围 itemCount~itemCount*5
 * @param itemCount  该维度参与计分的题目数量
 * @param normalized 归一化到 0.00 ~ 100.00 的分数
 */
public record DimensionScore<D extends Enum<D> & ScaleDimension>(
        D dimension,
        int rawSum,
        int itemCount,
        BigDecimal normalized
) {

    /**
     * 该维度分数对应的档位（偏低 / 中等 / 偏高）。
     *
     * <p>{@link Level#fromScore} 只看分数、不关心是哪个维度，
     * 所以这里一行都不用改就能服务两套量表。
     */
    public Level level() {
        return Level.fromScore(normalized);
    }
}
