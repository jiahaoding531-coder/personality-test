package com.example.personality.service;

import com.example.personality.domain.Dimension;
import com.example.personality.domain.DimensionScore;
import com.example.personality.domain.Level;
import com.example.personality.domain.ScoredItem;
import com.example.personality.exception.InvalidAnswersException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ScoringService 的单元测试。
 *
 * <p><b>注意这个类没有 @SpringBootTest。</b>因为 ScoringService 不依赖 Spring 容器，
 * 直接 {@code new} 出来就能测。这样的测试跑得飞快（毫秒级），也不需要数据库。
 * 这是"把核心逻辑和框架解耦"带来的实际好处。
 *
 * <p>本文件也是你的 JUnit 5 入门样本——用到的就三样东西：
 * <ul>
 *   <li>{@code @Test} 标记一个测试方法</li>
 *   <li>{@code assertEquals(期望值, 实际值)} 断言相等</li>
 *   <li>{@code assertThrows(异常类型, () -> 会抛异常的代码)} 断言抛异常</li>
 * </ul>
 */
class ScoringServiceTest {

    /**
     * 直接 new，不经过 Spring。
     * JUnit 对每个 @Test 方法都会新建一次测试类实例，所以这个字段天然是隔离的。
     */
    private final ScoringService service = new ScoringService();

    // ==========================================================
    // 第一组：反向计分（effectiveScore）
    // ==========================================================

    @Test
    @DisplayName("反向题：分值围绕中点 3 镜像翻转（1↔5、2↔4、3→3）")
    void reverseScoring_mirrorsAroundMidpoint() {
        assertEquals(5, service.effectiveScore(item(Dimension.OPENNESS, true, 1)));
        assertEquals(4, service.effectiveScore(item(Dimension.OPENNESS, true, 2)));
        assertEquals(3, service.effectiveScore(item(Dimension.OPENNESS, true, 3)), "中点 3 翻转后应该还是 3");
        assertEquals(2, service.effectiveScore(item(Dimension.OPENNESS, true, 4)));
        assertEquals(1, service.effectiveScore(item(Dimension.OPENNESS, true, 5)));
    }

    @Test
    @DisplayName("正向题：分值原样保留，不做任何翻转")
    void forwardQuestion_keepsRawScore() {
        for (int score = 1; score <= 5; score++) {
            assertEquals(score, service.effectiveScore(item(Dimension.OPENNESS, false, score)),
                    "正向题的 " + score + " 分不该被改动");
        }
    }

    // ==========================================================
    // 第二组：归一化的三个边界值
    // ==========================================================

    @Test
    @DisplayName("全部选最低分（1 分）→ 归一化到 0.00")
    void allLowestScores_normalizeToZero() {
        Map<Dimension, DimensionScore<Dimension>>result = service.score(uniformAnswers(4, 1, false));

        for (Dimension dimension : Dimension.values()) {
            DimensionScore<Dimension> score = result.get(dimension);
            assertEquals(4, score.rawSum(), dimension.label() + " 的原始分应为 4×1=4");
            assertBigDecimalEquals("0.00", score.normalized(), dimension.label() + " 应归一化到 0.00");
            assertEquals(Level.LOW, score.level());
        }
    }

    @Test
    @DisplayName("全部选最高分（5 分）→ 归一化到 100.00")
    void allHighestScores_normalizeToHundred() {
        Map<Dimension, DimensionScore<Dimension>>result = service.score(uniformAnswers(4, 5, false));

        for (Dimension dimension : Dimension.values()) {
            DimensionScore<Dimension> score = result.get(dimension);
            assertEquals(20, score.rawSum(), dimension.label() + " 的原始分应为 4×5=20");
            assertBigDecimalEquals("100.00", score.normalized(), dimension.label() + " 应归一化到 100.00");
            assertEquals(Level.HIGH, score.level());
        }
    }

    @Test
    @DisplayName("全部选中间分（3 分）→ 归一化到 50.00")
    void allMidpointScores_normalizeToFifty() {
        Map<Dimension, DimensionScore<Dimension>>result = service.score(uniformAnswers(4, 3, false));

        for (Dimension dimension : Dimension.values()) {
            DimensionScore<Dimension> score = result.get(dimension);
            assertEquals(12, score.rawSum());
            assertBigDecimalEquals("50.00", score.normalized(), dimension.label() + " 应归一化到 50.00");
            assertEquals(Level.MEDIUM, score.level());
        }
    }

    // ==========================================================
    // 第三组：反向计分与归一化的联合验证（最容易出错的地方）
    // ==========================================================

    @Test
    @DisplayName("全选 1 分的反向题 → 翻转成满分 → 归一化到 100.00（验证反向与归一化能正确串联）")
    void allReversedLowestScores_normalizeToHundred() {
        Map<Dimension, DimensionScore<Dimension>>result = service.score(uniformAnswers(4, 1, true));

        for (Dimension dimension : Dimension.values()) {
            DimensionScore<Dimension> score = result.get(dimension);
            assertEquals(20, score.rawSum(), "1 分反向翻转成 5 分，四题合计应为 20");
            assertBigDecimalEquals("100.00", score.normalized());
        }
    }

    @Test
    @DisplayName("正向与反向题混排时，原始分求和正确（一个手算过的具体场景）")
    void mixedItems_computeExpectedRawSum() {
        // 开放性维度 4 道题，手算：
        //   正向 5 分        → 5
        //   正向 4 分        → 4
        //   反向 2 分        → 6-2 = 4
        //   反向 1 分        → 6-1 = 5
        //   合计             → 18
        //   归一化 (18-4)/16*100 = 87.50
        List<ScoredItem<Dimension>>opennessItems = List.of(
                item(Dimension.OPENNESS, false, 5),
                item(Dimension.OPENNESS, false, 4),
                item(Dimension.OPENNESS, true, 2),
                item(Dimension.OPENNESS, true, 1)
        );

        Map<Dimension, DimensionScore<Dimension>>result = service.score(answersWithCustomDimension(Dimension.OPENNESS, opennessItems));
        DimensionScore<Dimension> openness = result.get(Dimension.OPENNESS);

        assertEquals(18, openness.rawSum(), "5 + 4 + (6-2) + (6-1) = 18");
        assertEquals(4, openness.itemCount());
        assertBigDecimalEquals("87.50", openness.normalized(), "(18-4)/16*100 = 87.50");
        assertEquals(Level.HIGH, openness.level());
    }

    @Test
    @DisplayName("每道题对总分的贡献恒为 6.25（4 题/维度时）")
    void eachItemContributesEqually() {
        // 这是归一化公式的一个隐含性质：100 / (4 × 4) = 6.25
        assertBigDecimalEquals("6.25", service.normalize(5, 4), "从 4 分涨到 5 分应恰好 +6.25");
        assertBigDecimalEquals("25.00", service.normalize(8, 4));
        assertBigDecimalEquals("75.00", service.normalize(16, 4));
    }

    // ==========================================================
    // 第四组：itemCount 是算出来的，不是硬编码 4
    // ==========================================================

    @Test
    @DisplayName("每维度换成 6 题时，归一化公式自动适配（不需要改代码）")
    void normalizationAdaptsToItemCount() {
        Map<Dimension, DimensionScore<Dimension>>result = service.score(uniformAnswers(6, 1, false));

        for (Dimension dimension : Dimension.values()) {
            DimensionScore<Dimension> score = result.get(dimension);
            assertEquals(6, score.itemCount(), "应该识别出每维度 6 题");
            assertEquals(6, score.rawSum());
            assertBigDecimalEquals("0.00", score.normalized(), "6 题全 1 分仍应归一到 0.00");
        }

        // 6 题全 5 分 → rawSum=30 → (30-6)/(36-6)*100 = 80/... 等等，是 (30-6)/(30-6)=1 → 100.00
        Map<Dimension, DimensionScore<Dimension>>maxResult = service.score(uniformAnswers(6, 5, false));
        assertBigDecimalEquals("100.00", maxResult.get(Dimension.OPENNESS).normalized());
    }

    // ==========================================================
    // 第五组：非法输入必须被拦住
    // ==========================================================

    @Test
    @DisplayName("某个维度一道题都没有 → 抛 InvalidAnswersException")
    void missingDimension_throws() {
        // 只作答 4 个维度，故意漏掉「情绪稳定性」
        List<ScoredItem<Dimension>>items = new ArrayList<>();
        for (Dimension dimension : Dimension.values()) {
            if (dimension == Dimension.EMOTIONAL_STABILITY) {
                continue;
            }
            for (int i = 0; i < 4; i++) {
                items.add(item(dimension, false, 3));
            }
        }

        InvalidAnswersException ex = assertThrows(InvalidAnswersException.class, () -> service.score(items));
        assertTrue(ex.getMessage().contains("情绪稳定性"),
                "异常信息里应该指出缺的是哪个维度，实际是：" + ex.getMessage());
    }

    @Test
    @DisplayName("分值越界（0 或 6）→ 抛 InvalidAnswersException")
    void outOfRangeScore_throws() {
        List<ScoredItem<Dimension>>tooLow = uniformAnswers(4, 3, false);
        tooLow.set(0, item(Dimension.OPENNESS, false, 0));
        assertThrows(InvalidAnswersException.class, () -> service.score(tooLow), "0 分应该被拒绝");

        List<ScoredItem<Dimension>>tooHigh = uniformAnswers(4, 3, false);
        tooHigh.set(0, item(Dimension.OPENNESS, false, 6));
        assertThrows(InvalidAnswersException.class, () -> service.score(tooHigh), "6 分应该被拒绝");
    }

    @Test
    @DisplayName("答案列表为空 / 为 null → 抛 InvalidAnswersException")
    void emptyAnswers_throws() {
        assertThrows(InvalidAnswersException.class, () -> service.score(List.of()));
        assertThrows(InvalidAnswersException.class, () -> service.score(null));
    }

    // ==========================================================
    // 第六组：返回值的结构约定
    // ==========================================================

    @Test
    @DisplayName("返回值必须是不可变 Map，且包含全部 5 个维度")
    void resultIsUnmodifiableAndComplete() {
        Map<Dimension, DimensionScore<Dimension>>result = service.score(uniformAnswers(4, 3, false));

        assertEquals(5, result.size(), "必须恰好包含 5 个维度");
        for (Dimension dimension : Dimension.values()) {
            assertTrue(result.containsKey(dimension), "缺少维度：" + dimension.label());
        }

        // 试图修改应抛 UnsupportedOperationException——防止调用方意外篡改结果
        assertThrowsExactly(UnsupportedOperationException.class,
                () -> result.put(Dimension.OPENNESS, null));
    }

    // ==========================================================
    // 测试辅助方法
    // ==========================================================

    private static ScoredItem<Dimension> item(Dimension dimension, boolean reverseScored, int rawScore) {
        return new ScoredItem<>(dimension, reverseScored, rawScore);
    }

    /** 5 个维度各 {@code itemsPerDimension} 题，全部同一分值、同一正反向设置。 */
    private static List<ScoredItem<Dimension>>uniformAnswers(int itemsPerDimension, int score, boolean reverse) {
        List<ScoredItem<Dimension>>items = new ArrayList<>();
        for (Dimension dimension : Dimension.values()) {
            for (int i = 0; i < itemsPerDimension; i++) {
                items.add(item(dimension, reverse, score));
            }
        }
        return items;
    }

    /** 指定某个维度用自定义的题目集合，其余维度用 4 题、3 分填充，保证计分能通过完整性校验。 */
    private static List<ScoredItem<Dimension>>answersWithCustomDimension(Dimension target, List<ScoredItem<Dimension>>customItems) {
        List<ScoredItem<Dimension>>items = new ArrayList<>();
        for (Dimension dimension : Dimension.values()) {
            if (dimension == target) {
                items.addAll(customItems);
            } else {
                for (int i = 0; i < 4; i++) {
                    items.add(item(dimension, false, 3));
                }
            }
        }
        return items;
    }

    /**
     * BigDecimal 的比较必须用 compareTo 而不是 equals。
     *
     * <p>因为 {@code new BigDecimal("50.0").equals(new BigDecimal("50.00"))} 是 <b>false</b>——
     * equals 会连小数位数（scale）一起比。这是 BigDecimal 最经典的坑，
     * 很多人的测试莫名其妙失败就是因为这个。
     */
    private static void assertBigDecimalEquals(String expected, BigDecimal actual) {
        assertBigDecimalEquals(expected, actual, null);
    }

    private static void assertBigDecimalEquals(String expected, BigDecimal actual, String message) {
        BigDecimal expectedValue = new BigDecimal(expected);
        if (expectedValue.compareTo(actual) != 0) {
            throw new AssertionError(
                    (message == null ? "" : message + " —— ")
                            + "期望 " + expected + "，实际 " + actual.toPlainString());
        }
    }
}
