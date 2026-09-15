package com.example.personality.service;

import com.example.personality.domain.PlaceCandidate;
import com.example.personality.domain.PlaceTraits;
import com.example.personality.domain.RecommendationContext;
import com.example.personality.domain.ScoredPlace;
import com.example.personality.domain.TravelDimension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 推荐引擎的单元测试。
 *
 * <p><b>不需要数据库、不需要 Spring 容器</b>——引擎是纯逻辑类，
 * 直接 {@code new} 出来喂构造好的数据就行。
 * 跑一次只要毫秒级，可以放心改算法、随时验证。
 *
 * <p>计划书第二十四节的第一阶段目标就是这个：
 * 「写出推荐算法 → 根据一个人的偏好输出 Top 3 推荐」。
 */
class RecommendationEngineTest {

    private final RecommendationEngine engine = new RecommendationEngine();

    /** 一个「只剩很多时间、没有定位」的宽松场景，让时间/距离不干扰对兴趣匹配的验证。 */
    private static final RecommendationContext RELAXED =
            RecommendationContext.of(LocalTime.of(10, 0), 600);

    // ==========================================================
    // 兴趣匹配：算法的核心
    // ==========================================================

    /**
     * <b>这是最重要的一条测试。</b>
     *
     * <p>用户只在乎「自然」，那么推荐的排序就应该完全由地点的 nature 属性决定——
     * 其它维度再高也不该影响结果。
     */
    @Test
    @DisplayName("只在乎自然时，排序完全由地点的 nature 属性决定")
    void pureNaturePreference_ordersByNatureAttribute() {
        Map<TravelDimension, Integer> pref = only(TravelDimension.NATURE, 100);

        List<PlaceCandidate> places = List.of(
                place("文化古城", traits(10, 95, 30, 40, 20, 70, 40)),
                place("湿地公园", traits(95, 20, 10, 60, 30, 20, 60)),
                place("街边夜市", traits(20, 30, 80, 30, 10, 95, 30)),
                place("山间茶园", traits(80, 40, 40, 70, 60, 15, 65))
        );

        List<ScoredPlace> top = engine.recommend(pref, places, RELAXED, 3);

        assertEquals("湿地公园", top.get(0).place().name(), "nature=95 应该排第一");
        assertEquals("山间茶园", top.get(1).place().name(), "nature=80 应该排第二");
        assertEquals("街边夜市", top.get(2).place().name(), "nature=20 应该排第三");
        // 文化古城 nature=10，被挤出去了
        assertFalse(top.stream().anyMatch(p -> p.place().name().equals("文化古城")),
                "nature 最低的不该进 Top 3");
    }

    @Test
    @DisplayName("只在乎美食时，排序跟着 food 走（验证偏好真的在切换排序）")
    void pureFoodPreference_ordersByFoodAttribute() {
        Map<TravelDimension, Integer> pref = only(TravelDimension.FOOD, 100);

        List<PlaceCandidate> places = List.of(
                place("湿地公园", traits(95, 20, 10, 60, 30, 20, 60)),
                place("街边夜市", traits(20, 30, 80, 30, 10, 95, 30)),
                place("老字号面馆", traits(0, 40, 95, 20, 40, 60, 15))
        );

        List<ScoredPlace> top = engine.recommend(pref, places, RELAXED, 3);

        assertEquals("老字号面馆", top.get(0).place().name());
        assertEquals("街边夜市", top.get(1).place().name());
        assertEquals("湿地公园", top.get(2).place().name());
    }

    /**
     * 用户同时在乎多个维度时，应该是在这些维度上的**综合**表现好，
     * 而不是在某一个维度上极端。
     */
    @Test
    @DisplayName("同时在乎自然和人文时，两个维度都高的胜出")
    void multiplePreferences_favourBalancedPlaces() {
        Map<TravelDimension, Integer> pref = new EnumMap<>(TravelDimension.class);
        pref.put(TravelDimension.NATURE, 100);
        pref.put(TravelDimension.CULTURE, 100);

        List<PlaceCandidate> places = List.of(
                place("纯自然", traits(95, 5, 0, 50, 40, 20, 60)),
                place("纯人文", traits(5, 95, 0, 50, 40, 20, 60)),
                place("两者兼顾", traits(80, 80, 0, 50, 40, 20, 60))
        );

        List<ScoredPlace> top = engine.recommend(pref, places, RELAXED, 3);

        assertEquals("两者兼顾", top.get(0).place().name(),
                "自然和人文都高的地方，应该胜过只在单个维度突出的");
    }

    @Test
    @DisplayName("用户全填最低分（所有偏好为 0）时退化为等权，不报错")
    void allZeroPreference_fallsBackToEqualWeight() {
        Map<TravelDimension, Integer> pref = only(TravelDimension.NATURE, 0);

        List<PlaceCandidate> places = List.of(
                place("全面均衡", traits(70, 70, 70, 70, 70, 70, 70)),
                place("样样稀松", traits(10, 10, 10, 10, 10, 10, 10))
        );

        List<ScoredPlace> top = engine.recommend(pref, places, RELAXED, 3);

        assertEquals(2, top.size(), "不该因为分母为 0 就崩掉");
        assertEquals("全面均衡", top.get(0).place().name());
    }

    // ==========================================================
    // 硬过滤：不营业 / 时间不够
    // ==========================================================

    @Test
    @DisplayName("已关门的地方被直接排除，而不是排在后面")
    void closedPlacesAreExcluded() {
        Map<TravelDimension, Integer> pref = only(TravelDimension.NATURE, 100);

        PlaceCandidate museum = place("博物馆", traits(95, 90, 0, 60, 30, 50, 50),
                LocalTime.of(9, 0), LocalTime.of(17, 0));
        PlaceCandidate park = place("公园", traits(80, 30, 0, 60, 30, 50, 50));

        // 现在是晚上 20:00，博物馆关门了
        RecommendationContext evening = RecommendationContext.of(LocalTime.of(20, 0), 300);

        List<ScoredPlace> top = engine.recommend(pref, List.of(museum, park), evening, 3);

        assertEquals(1, top.size(), "关门的应该被排除，只剩公园");
        assertEquals("公园", top.get(0).place().name());
    }

    @Test
    @DisplayName("全天开放的地方（营业时间为 null）任何时刻都算营业")
    void alwaysOpenPlacesAreNeverFiltered() {
        Map<TravelDimension, Integer> pref = only(TravelDimension.NATURE, 100);
        PlaceCandidate park = place("公园", traits(90, 30, 0, 60, 30, 50, 50));   // 无营业时间

        for (int hour : new int[]{0, 6, 12, 18, 23}) {
            List<ScoredPlace> top = engine.recommend(
                    pref, List.of(park), RecommendationContext.of(LocalTime.of(hour, 0), 600), 3);
            assertEquals(1, top.size(), hour + " 点也该营业");
        }
    }

    @Test
    @DisplayName("建议停留时间超过剩余时间的被排除")
    void placesThatDoNotFitAreExcluded() {
        Map<TravelDimension, Integer> pref = only(TravelDimension.NATURE, 100);

        PlaceCandidate quick = place("小公园", traits(80, 30, 0, 60, 30, 50, 50), 30);
        PlaceCandidate longVisit = place("大湿地", traits(95, 30, 0, 60, 30, 50, 50), 240);

        // 只剩 1 小时
        RecommendationContext shortTime = RecommendationContext.of(LocalTime.of(10, 0), 60);
        List<ScoredPlace> top = engine.recommend(pref, List.of(quick, longVisit), shortTime, 3);

        assertEquals(1, top.size());
        assertEquals("小公园", top.get(0).place().name(),
                "需要 4 小时的地方，在只剩 1 小时时不该被推荐");
    }

    @Test
    @DisplayName("所有候选都被过滤掉时返回空列表，不抛异常")
    void allFilteredOut_returnsEmptyList() {
        Map<TravelDimension, Integer> pref = only(TravelDimension.NATURE, 100);
        PlaceCandidate museum = place("博物馆", traits(95, 90, 0, 60, 30, 50, 50),
                LocalTime.of(9, 0), LocalTime.of(17, 0));

        List<ScoredPlace> top = engine.recommend(
                pref, List.of(museum), RecommendationContext.of(LocalTime.of(22, 0), 300), 3);

        assertTrue(top.isEmpty());
    }

    // ==========================================================
    // 距离
    // ==========================================================

    @Test
    @DisplayName("同样符合偏好的两个地方，近的排前面")
    void closerPlaceWinsWhenInterestIsEqual() {
        Map<TravelDimension, Integer> pref = only(TravelDimension.NATURE, 100);

        // 用杭州西湖附近做原点
        double lat = 30.2420, lon = 120.1400;

        PlaceCandidate near = placeAt("近的公园", 30.2450, 120.1420,
                traits(90, 30, 0, 60, 30, 50, 50));     // 约 0.4 公里
        PlaceCandidate far = placeAt("远的公园", 30.3100, 120.2000,
                traits(90, 30, 0, 60, 30, 50, 50));     // 约 9 公里

        RecommendationContext ctx = new RecommendationContext(
                LocalTime.of(10, 0), 600, lat, lon, 20.0);

        List<ScoredPlace> top = engine.recommend(pref, List.of(far, near), ctx, 3);

        assertEquals("近的公园", top.get(0).place().name(), "属性相同时，近的应该排前面");
        assertTrue(top.get(0).distanceKm() < top.get(1).distanceKm());
    }

    @Test
    @DisplayName("超出最大半径的地方被排除")
    void placesBeyondMaxRadiusAreExcluded() {
        Map<TravelDimension, Integer> pref = only(TravelDimension.NATURE, 100);
        PlaceCandidate farAway = placeAt("很远的地方", 31.0000, 121.0000,
                traits(100, 100, 100, 100, 100, 100, 100));

        RecommendationContext ctx = new RecommendationContext(
                LocalTime.of(10, 0), 600, 30.2420, 120.1400, 10.0);

        assertTrue(engine.recommend(pref, List.of(farAway), ctx, 3).isEmpty());
    }

    @Test
    @DisplayName("没有定位时距离因素失效，但仍能正常排序")
    void withoutLocation_distanceIsIgnored() {
        Map<TravelDimension, Integer> pref = only(TravelDimension.NATURE, 100);
        PlaceCandidate a = placeAt("甲", 30.20, 120.10, traits(90, 0, 0, 0, 0, 0, 0));
        PlaceCandidate b = placeAt("乙", 31.50, 121.90, traits(80, 0, 0, 0, 0, 0, 0));

        List<ScoredPlace> top = engine.recommend(pref, List.of(a, b),
                RecommendationContext.of(LocalTime.of(10, 0), 600), 3);

        assertEquals(2, top.size());
        assertEquals("甲", top.get(0).place().name());
        assertEquals(null, top.get(0).distanceKm(), "没有定位时距离应为 null");
    }

    // ==========================================================
    // 质量与排序
    // ==========================================================

    @Test
    @DisplayName("兴趣相同时，质量分高的排前面")
    void higherQualityWinsWhenInterestIsEqual() {
        Map<TravelDimension, Integer> pref = only(TravelDimension.NATURE, 100);

        PlaceCandidate good = placeWithQuality("口碑好的", traits(85, 0, 0, 0, 0, 0, 0), 95);
        PlaceCandidate meh = placeWithQuality("一般的", traits(85, 0, 0, 0, 0, 0, 0), 60);

        List<ScoredPlace> top = engine.recommend(pref, List.of(meh, good), RELAXED, 3);

        assertEquals("口碑好的", top.get(0).place().name());
    }

    /**
     * <b>这是一条"不变量"测试，防的是一个会 500 的 bug。</b>
     *
     * <p>数据库上有 {@code ck_recommendations_score CHECK (score BETWEEN 0 AND 1)}。
     * 引擎曾经把质量分写成"上下浮动"（0.85~1.15），于是 quality=92、
     * 用户偏好又集中的时候算出来 1.04——存库时违反约束，接口 500。
     *
     * <p>这里构造的是**最坏情况**：所有维度都完美匹配（interest=1.0）、
     * 质量分拉满（quality=100）、没有定位所以距离系数=1.0。
     * 三个因子都取到各自的最大值，score 必须仍然 ≤ 1。
     */
    @Test
    @DisplayName("分数永远不超过 1——数据库 CHECK 约束的上限，也是百分比不超过 100% 的前提")
    void scoreNeverExceedsOne() {
        Map<TravelDimension, Integer> pref = new EnumMap<>(TravelDimension.class);
        for (TravelDimension d : TravelDimension.values()) {
            if (d.affectsPlaceChoice()) {
                pref.put(d, 100);   // 每个参与排序的维度都拉满
            }
        }
        PlaceCandidate perfect = placeWithQuality(
                "满分地点", traits(100, 100, 100, 100, 100, 100, 100), 100);

        ScoredPlace top = engine.recommend(pref, List.of(perfect), RELAXED, 3).get(0);

        assertTrue(top.score() <= 1.0, "score 不能超过 1，实际 " + top.score());
        assertTrue(top.scorePercent() <= 100,
                "百分比不能超过 100，实际 " + top.scorePercent() + "%");
    }

    @Test
    @DisplayName("结果按分数降序排列")
    void resultsAreSortedByScoreDescending() {
        Map<TravelDimension, Integer> pref = only(TravelDimension.NATURE, 100);
        List<PlaceCandidate> places = new ArrayList<>();
        for (int i = 10; i <= 90; i += 10) {
            places.add(place("地点" + i, traits(i, 0, 0, 0, 0, 0, 0)));
        }

        List<ScoredPlace> top = engine.recommend(pref, places, RELAXED, 5);

        assertEquals(5, top.size(), "limit 应该生效");
        for (int i = 1; i < top.size(); i++) {
            assertTrue(top.get(i - 1).score() >= top.get(i).score(),
                    "第 " + i + " 个的分数不该高于前一个");
        }
        assertEquals("地点90", top.get(0).place().name());
    }

    @Test
    @DisplayName("返回的 topMatches 能解释为什么推荐它")
    void topMatchesExplainTheRecommendation() {
        Map<TravelDimension, Integer> pref = new EnumMap<>(TravelDimension.class);
        pref.put(TravelDimension.NATURE, 100);
        pref.put(TravelDimension.FOOD, 80);
        pref.put(TravelDimension.CULTURE, 10);

        // 只看一个地点，检查解释内容
        PlaceCandidate p = place("测试地点", traits(90, 10, 85, 0, 0, 0, 0));
        List<ScoredPlace> top = engine.recommend(pref, List.of(p), RELAXED, 3);

        List<ScoredPlace.MatchedDimension> matches = top.get(0).topMatches();
        assertFalse(matches.isEmpty(), "应该给出匹配维度");
        // 贡献最大的应该是 nature（偏好 100 × 属性 90 = 9000）
        assertEquals(TravelDimension.NATURE, matches.get(0).dimension());
        // 贡献第二的是 food（80 × 85 = 6800）
        assertEquals(TravelDimension.FOOD, matches.get(1).dimension());
    }

    // ==========================================================
    // 球面距离
    // ==========================================================

    @Test
    @DisplayName("Haversine 距离计算正确（用已知距离校验）")
    void haversineDistanceIsCorrect() {
        // 杭州西湖 → 上海人民广场，直线距离约 160 公里
        double d = RecommendationEngine.haversineKm(30.2420, 120.1400, 31.2304, 121.4737);
        assertTrue(d > 150 && d < 175, "杭州到上海应该在 150~175 公里之间，实际 " + d);

        // 同一个点距离为 0
        assertEquals(0.0, RecommendationEngine.haversineKm(30.0, 120.0, 30.0, 120.0), 0.0001);

        // 纬线上 1 度约 111 公里
        double oneDegreeLat = RecommendationEngine.haversineKm(30.0, 120.0, 31.0, 120.0);
        assertTrue(Math.abs(oneDegreeLat - 111.2) < 1.0,
                "纬度 1 度应约 111 公里，实际 " + oneDegreeLat);
    }

    // ==========================================================
    // 测试夹具
    // ==========================================================

    private static Map<TravelDimension, Integer> only(TravelDimension dimension, int value) {
        Map<TravelDimension, Integer> map = new EnumMap<>(TravelDimension.class);
        for (TravelDimension d : TravelDimension.values()) {
            map.put(d, d == dimension ? value : 0);
        }
        return map;
    }

    private static PlaceTraits traits(int nature, int culture, int food,
                                      int photography, int hiddenGems, int lively, int walking) {
        return new PlaceTraits(nature, culture, food, photography, hiddenGems, lively, walking);
    }

    private static PlaceCandidate place(String name, PlaceTraits traits) {
        return place(name, traits, 60);
    }

    private static PlaceCandidate place(String name, PlaceTraits traits, int suggestedMinutes) {
        return new PlaceCandidate(1L, name, "NATURE", 30.2420, 120.1400, traits,
                80, 0, suggestedMinutes, null, null, "测试地点");
    }

    private static PlaceCandidate place(String name, PlaceTraits traits,
                                        LocalTime openFrom, LocalTime openTo) {
        return new PlaceCandidate(1L, name, "MUSEUM", 30.2420, 120.1400, traits,
                80, 0, 60, openFrom, openTo, "测试地点");
    }

    private static PlaceCandidate placeAt(String name, double lat, double lon, PlaceTraits traits) {
        return new PlaceCandidate(1L, name, "NATURE", lat, lon, traits,
                80, 0, 60, null, null, "测试地点");
    }

    private static PlaceCandidate placeWithQuality(String name, PlaceTraits traits, int quality) {
        return new PlaceCandidate(1L, name, "NATURE", 30.2420, 120.1400, traits,
                quality, 0, 60, null, null, "测试地点");
    }
}
