package com.example.personality.service;

import com.example.personality.domain.DimensionScore;
import com.example.personality.domain.ScaleDimension;
import com.example.personality.domain.ScoredItem;
import com.example.personality.domain.TravelDimension;
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
 * {@link ScoringService} 对<b>旅行量表</b>的单元测试。
 *
 * <h2>为什么这个文件很重要（不只是"多几个测试"）</h2>
 *
 * <p>{@code ScoringService.score()} 被泛型化之后，如果<b>只用</b>人格量表去测，
 * 那么跑 95 遍也说明不了泛型是对的——它只证明了"没改坏人格量表"。
 * 一个泛型方法只有被<b>第二个类型参数</b>实例化过一次，
 * 才算真的被验证了。
 *
 * <p>所以这个文件和 {@code ScoringServiceTest} 是一对：那个守"人格量表的结果没变"，
 * 这个守"泛型对另一套枚举同样成立"。
 *
 * <p>同样不需要 Spring、不需要数据库——直接 {@code new ScoringService()}。
 *
 * <p>旅行量表的两个形状特点（都来自 V6 迁移脚本的说明）：
 * <ul>
 *   <li>每维度<b>只有 1 道题</b>，所以分数只能落在 0/25/50/75/100 五档</li>
 *   <li><b>没有反向题</b>——对 8 道题的短问卷，反向题会让语义变得别扭</li>
 * </ul>
 */
class ScoringServiceTravelTest {

    private final ScoringService service = new ScoringService();

    // ==========================================================
    // 第一组：旅行量表的真实形状（8 维 × 1 题）
    // ==========================================================

    @Test
    @DisplayName("每维度只有 1 题时，分数落在 0/25/50/75/100 五档上")
    void singleQuestionPerDimension_mapsToFiveSteps() {
        // 归一化公式 (rawSum - itemCount) / (itemCount * 4) * 100
        // itemCount = 1 时化简成 (score - 1) * 25，所以五档是等距的
        assertEquals(0, scoreOfNATURE(travelAnswers(1)));
        assertEquals(25, scoreOfNATURE(travelAnswers(2)));
        assertEquals(50, scoreOfNATURE(travelAnswers(3)));
        assertEquals(75, scoreOfNATURE(travelAnswers(4)));
        assertEquals(100, scoreOfNATURE(travelAnswers(5)));
    }

    @Test
    @DisplayName("8 个维度全部算出来，且 key 恰好是那 8 个旅行维度")
    void allEightDimensionsArePresent() {
        Map<TravelDimension, DimensionScore<TravelDimension>> result = service.score(
                travelAnswers(4), TravelDimension.class);

        assertEquals(8, result.size(), "旅行量表一共 8 个维度");

        // 遍历顺序必须等于枚举的声明顺序——ProfileQueryService 出来的维度列表
        // 是按 Map 顺序渲染的，顺序不稳会让前端每次显示的顺序都不一样
        List<TravelDimension> actualOrder = new ArrayList<>(result.keySet());
        assertEquals(List.of(TravelDimension.values()), actualOrder);

        for (TravelDimension dimension : TravelDimension.values()) {
            assertTrue(result.containsKey(dimension), "缺少维度：" + dimension.label());
        }
    }

    @Test
    @DisplayName("返回值不可变——调用方改不了计分结果")
    void resultIsUnmodifiable() {
        Map<TravelDimension, DimensionScore<TravelDimension>> result = service.score(
                travelAnswers(3), TravelDimension.class);

        assertThrowsExactly(UnsupportedOperationException.class,
                () -> result.put(TravelDimension.NATURE, null));
    }

    @Test
    @DisplayName("维度分是 DimensionScore<TravelDimension>，dimension 字段和 Map 的 key 对得上")
    void dimensionFieldMatchesMapKey() {
        Map<TravelDimension, DimensionScore<TravelDimension>> result = service.score(
                travelAnswers(5), TravelDimension.class);

        DimensionScore<TravelDimension> nature = result.get(TravelDimension.NATURE);
        assertEquals(TravelDimension.NATURE, nature.dimension());
        assertEquals(1, nature.itemCount(), "旅行量表每维度 1 道题");
        assertEquals(5, nature.rawSum());
    }

    // ==========================================================
    // 第二组：完整性校验（这条同时验证了 ScaleDimension 接口在起作用）
    // ==========================================================

    @Test
    @DisplayName("漏答一个旅行维度 → 抛 InvalidAnswersException，且异常信息里是中文维度名")
    void missingDimension_throws() {
        // 只答 7 个维度，故意漏掉「自然风光」
        List<ScoredItem<TravelDimension>> items = new ArrayList<>();
        for (TravelDimension dimension : TravelDimension.values()) {
            if (dimension == TravelDimension.NATURE) {
                continue;
            }
            items.add(item(dimension, 3));
        }

        InvalidAnswersException ex = assertThrows(InvalidAnswersException.class,
                () -> service.score(items, TravelDimension.class));

        // ⚠️ 这条断言看着普通，其实是 ScaleDimension 接口存在的唯一理由：
        // 计分器要在报错里写出人看得懂的名字，但它面对的可能是任意一套枚举。
        // 没有那个接口，这里就只能写死"如果是旅行维度就查 TravelDimension"的分支。
        assertTrue(ex.getMessage().contains("自然风光"),
                "异常信息里应该指出缺的是哪个维度，实际是：" + ex.getMessage());
    }

    // ==========================================================
    // 第三组：分数精度（V9 迁移脚本里"为什么用 NUMERIC 不用 INTEGER"的活证据）
    // ==========================================================

    @Test
    @DisplayName("某维度有 2 道题时会出现 12.50 这种小数——这就是画像表不能用 INTEGER 的原因")
    void twoQuestionsPerDimension_producesFractionalScores() {
        // 自然风光 2 道题：答 1 分和 2 分 → rawSum = 3
        // 归一化 (3 - 2) / (10 - 2) * 100 = 12.50
        List<ScoredItem<TravelDimension>> items = new ArrayList<>();
        items.add(item(TravelDimension.NATURE, 1));
        items.add(item(TravelDimension.NATURE, 2));
        for (TravelDimension dimension : TravelDimension.values()) {
            if (dimension != TravelDimension.NATURE) {
                items.add(item(dimension, 3));
            }
        }

        Map<TravelDimension, DimensionScore<TravelDimension>> result =
                service.score(items, TravelDimension.class);

        DimensionScore<TravelDimension> nature = result.get(TravelDimension.NATURE);
        assertEquals(2, nature.itemCount());
        assertEquals(3, nature.rawSum());
        assertBigDecimalEquals("12.50", nature.normalized(),
                "旅行每维 1 题是今天的巧合，题量一加就会出现两位小数");
    }

    // ==========================================================
    // 第四组：证明泛型是真的通用，而不是"恰好支持这两套枚举"
    // ==========================================================

    @Test
    @DisplayName("第三套自定义量表也能算——证明 score() 对任意维度枚举通用")
    void worksWithAThirdScaleDefinedOnlyInTests() {
        // 一个只存在于测试里的量表。如果将来有人把 Dimension 或 TravelDimension
        // 硬编码回 score() 里（比如偷偷加一句 if (enumType == TravelDimension.class)），
        // 只有这条测试能戳穿它——前两套枚举都会"碰巧"继续工作。
        List<ScoredItem<FakeDimension>> items = List.of(
                new ScoredItem<>(FakeDimension.ALPHA, false, 5),
                new ScoredItem<>(FakeDimension.BETA, false, 3),
                new ScoredItem<>(FakeDimension.GAMMA, false, 1));

        Map<FakeDimension, DimensionScore<FakeDimension>> result =
                service.score(items, FakeDimension.class);

        assertEquals(3, result.size());
        assertBigDecimalEquals("100.00", result.get(FakeDimension.ALPHA).normalized());
        assertBigDecimalEquals("50.00", result.get(FakeDimension.BETA).normalized());
        assertBigDecimalEquals("0.00", result.get(FakeDimension.GAMMA).normalized());
    }

    /** 测试专用的第三套"量表"。实现 {@link ScaleDimension} 就够了，计分器不需要知道别的。 */
    private enum FakeDimension implements ScaleDimension {
        ALPHA("甲"), BETA("乙"), GAMMA("丙");

        private final String label;

        FakeDimension(String label) {
            this.label = label;
        }

        @Override
        public String label() {
            return label;
        }
    }

    // ==========================================================
    // 测试辅助方法
    // ==========================================================

    /** 一道旅行题。注意没有 reverseScored 参数——旅行量表全部是正向计分（见 V6）。 */
    private static ScoredItem<TravelDimension> item(TravelDimension dimension, int rawScore) {
        return new ScoredItem<>(dimension, false, rawScore);
    }

    /** 8 个维度各 1 题、全部同一分值。 */
    private static List<ScoredItem<TravelDimension>> travelAnswers(int score) {
        List<ScoredItem<TravelDimension>> items = new ArrayList<>();
        for (TravelDimension dimension : TravelDimension.values()) {
            items.add(item(dimension, score));
        }
        return items;
    }

    private int scoreOfNATURE(List<ScoredItem<TravelDimension>> items) {
        return service.score(items, TravelDimension.class)
                .get(TravelDimension.NATURE)
                .normalized()
                .intValueExact();
    }

    /** 比较 BigDecimal 必须用 compareTo，不能用 equals（equals 会连小数位数一起比）。 */
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
