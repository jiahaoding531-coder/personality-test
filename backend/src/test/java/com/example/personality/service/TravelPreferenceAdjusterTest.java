package com.example.personality.service;

import com.example.personality.domain.PlaceTraits;
import com.example.personality.domain.TravelDimension;
import com.example.personality.service.TravelPreferenceAdjuster.FeedbackSignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TravelPreferenceAdjuster} 的单元测试。
 *
 * <p>不需要数据库、不需要 Spring——纯逻辑类，直接 {@code new} 出来喂数据。
 */
class TravelPreferenceAdjusterTest {

    private final TravelPreferenceAdjuster adjuster = new TravelPreferenceAdjuster();

    // ==========================================================
    // 第一组：修正的基本方向
    // ==========================================================

    @Test
    @DisplayName("没有反馈时，有效画像 == 问卷画像（一个数字都不变）")
    void noFeedback_keepsQuestionnaire() {
        Map<TravelDimension, Integer> questionnaire = pref(TravelDimension.NATURE, 75);

        Map<TravelDimension, Integer> effective = adjuster.effectivePreference(questionnaire, List.of());

        assertEquals(questionnaire, effective);
    }

    @Test
    @DisplayName("👎 下调该维度，👍 上调，其它维度不受影响")
    void feedbackMovesOnlyTheAttributedDimension() {
        Map<TravelDimension, Integer> questionnaire = pref(TravelDimension.NATURE, 50);

        Map<TravelDimension, Integer> afterDislike = adjuster.effectivePreference(
                questionnaire, List.of(new FeedbackSignal(TravelDimension.NATURE, false)));
        assertEquals(40, afterDislike.get(TravelDimension.NATURE), "👎 应该下调 10");
        assertEquals(50, afterDislike.get(TravelDimension.FOOD), "别的维度不该动");

        Map<TravelDimension, Integer> afterLike = adjuster.effectivePreference(
                questionnaire, List.of(new FeedbackSignal(TravelDimension.NATURE, true)));
        assertEquals(60, afterLike.get(TravelDimension.NATURE), "👍 应该上调 10");
    }

    @Test
    @DisplayName("多条同方向反馈会累加（这才是'用得越多越准'的机制）")
    void repeatedFeedbackAccumulates() {
        Map<TravelDimension, Integer> questionnaire = pref(TravelDimension.NATURE, 75);

        Map<TravelDimension, Integer> effective = adjuster.effectivePreference(questionnaire, List.of(
                new FeedbackSignal(TravelDimension.NATURE, false),
                new FeedbackSignal(TravelDimension.NATURE, false)));

        assertEquals(55, effective.get(TravelDimension.NATURE), "两次 👎 应该累计下调 20");
    }

    @Test
    @DisplayName("正负反馈相互抵消，方向相反时不累积")
    void oppositeFeedbackCancelsOut() {
        Map<TravelDimension, Integer> questionnaire = pref(TravelDimension.NATURE, 50);

        Map<TravelDimension, Integer> effective = adjuster.effectivePreference(questionnaire, List.of(
                new FeedbackSignal(TravelDimension.NATURE, false),
                new FeedbackSignal(TravelDimension.NATURE, true)));

        assertEquals(50, effective.get(TravelDimension.NATURE), "一正一负应该回到原值");
    }

    // ==========================================================
    // 第二组：边界——这两条是这个类最容易出错的地方
    // ==========================================================

    @Test
    @DisplayName("累计修正有上限：连点 8 次 👎 也不会把维度压到 0")
    void totalDeltaIsCapped() {
        Map<TravelDimension, Integer> questionnaire = pref(TravelDimension.NATURE, 50);

        Map<TravelDimension, Integer> effective = adjuster.effectivePreference(questionnaire,
                java.util.Collections.nCopies(8, new FeedbackSignal(TravelDimension.NATURE, false)));

        // 8 次 × 10 = -80，但上限是 -40
        assertEquals(10, effective.get(TravelDimension.NATURE),
                "修正量封顶在 ±" + TravelPreferenceAdjuster.MAX_TOTAL_DELTA + "，问卷的基本盘要保住");
    }

    @Test
    @DisplayName("结果被夹在 0~100：问卷 5 分时再 👎 也不会变成负数")
    void effectivePreferenceStaysInRange() {
        Map<TravelDimension, Integer> low = pref(TravelDimension.NATURE, 5);
        Map<TravelDimension, Integer> after = adjuster.effectivePreference(
                low, List.of(new FeedbackSignal(TravelDimension.NATURE, false)));
        assertEquals(0, after.get(TravelDimension.NATURE), "不能小于 0");

        Map<TravelDimension, Integer> high = pref(TravelDimension.NATURE, 95);
        Map<TravelDimension, Integer> afterLike = adjuster.effectivePreference(
                high, List.of(new FeedbackSignal(TravelDimension.NATURE, true)));
        assertEquals(100, afterLike.get(TravelDimension.NATURE), "不能大于 100");
    }

    @Test
    @DisplayName("PLANNING 不参与地点排序，所以反馈不该动它")
    void planningIsNeverAdjusted() {
        Map<TravelDimension, Integer> questionnaire = pref(TravelDimension.PLANNING, 50);

        Map<TravelDimension, Integer> effective = adjuster.effectivePreference(questionnaire,
                List.of(new FeedbackSignal(TravelDimension.PLANNING, false)));

        assertEquals(50, effective.get(TravelDimension.PLANNING),
                "调它没有意义——它根本不影响推荐哪个地点");
    }

    // ==========================================================
    // 第三组：归因（启发式）
    // ==========================================================

    @Test
    @DisplayName("归因到「用户偏好 × 地点属性」最大的那个维度——即返回给前端的 reasons[0]")
    void dominantDimensionMatchesTheTopReason() {
        // 用户最在乎自然(90)，这个地方自然 95、美食 10 → 自然胜出
        Map<TravelDimension, Integer> questionnaire = new EnumMap<>(TravelDimension.class);
        questionnaire.put(TravelDimension.NATURE, 90);
        questionnaire.put(TravelDimension.FOOD, 80);
        PlaceTraits traits = traits(95, 10, 10, 10, 10, 10, 10);

        assertEquals(TravelDimension.NATURE, adjuster.dominantDimension(questionnaire, traits));
    }

    @Test
    @DisplayName("用户对某维度没兴趣时，地点在那方面再突出也不算数")
    void dominantDimensionIgnoresUnwantedTraits() {
        // 用户只要美食(100)，对自然毫无兴趣(0)。这个地方自然 100、美食 60 → 归到美食
        Map<TravelDimension, Integer> questionnaire = new EnumMap<>(TravelDimension.class);
        questionnaire.put(TravelDimension.NATURE, 0);
        questionnaire.put(TravelDimension.FOOD, 100);
        PlaceTraits traits = traits(100, 10, 60, 10, 10, 10, 10);

        assertEquals(TravelDimension.FOOD, adjuster.dominantDimension(questionnaire, traits));
    }

    @Test
    @DisplayName("问卷全填 0 时归因不出结果，返回 null（而不是随便挑一个）")
    void dominantDimensionIsNullWhenNothingContributes() {
        Map<TravelDimension, Integer> allZero = new EnumMap<>(TravelDimension.class);
        for (TravelDimension dimension : TravelDimension.values()) {
            allZero.put(dimension, 0);
        }

        assertNull(adjuster.dominantDimension(allZero, traits(100, 100, 100, 100, 100, 100, 100)),
                "没有任何维度有贡献时，不该硬猜一个出来");
    }

    @Test
    @DisplayName("adjustedDimensions 只列出真正被改动过的维度，供前端解释")
    void adjustedDimensionsListsOnlyChangedOnes() {
        List<TravelDimension> adjusted = adjuster.adjustedDimensions(List.of(
                new FeedbackSignal(TravelDimension.NATURE, false),
                new FeedbackSignal(TravelDimension.FOOD, true),
                new FeedbackSignal(TravelDimension.NATURE, true)));   // 这一条把自然的修正抵消了

        assertEquals(List.of(TravelDimension.FOOD), adjusted);
        assertTrue(adjusted.contains(TravelDimension.FOOD));
    }

    // ==========================================================
    // 测试辅助方法
    // ==========================================================

    private static Map<TravelDimension, Integer> pref(TravelDimension dimension, int value) {
        Map<TravelDimension, Integer> map = new EnumMap<>(TravelDimension.class);
        for (TravelDimension d : TravelDimension.values()) {
            map.put(d, d == dimension ? value : 50);
        }
        return map;
    }

    private static PlaceTraits traits(int nature, int culture, int food,
                                      int photography, int hiddenGems, int lively, int walking) {
        return new PlaceTraits(nature, culture, food, photography, hiddenGems, lively, walking);
    }
}
