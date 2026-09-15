package com.example.personality.service;

import com.example.personality.domain.PlaceCandidate;
import com.example.personality.domain.PlaceTraits;
import com.example.personality.domain.RecommendationContext;
import com.example.personality.domain.ScoredPlace;
import com.example.personality.domain.TravelDimension;
import com.example.personality.domain.TravelState;
import com.example.personality.domain.Weather;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * 测试地点的默认室内程度：<b>完全户外</b>。
     *
     * <p>取 0 是为了和数据库里 {@code places.indoor} 的 DEFAULT 保持一致，
     * 也是为了"万一哪天给这些测试加上了天气，变化会很明显"——
     * 完全户外的地方受天气影响最大，测出来的差异不会被淹没。
     */
    private static final int OUTDOOR = 0;

    // ==========================================================
    // 当前状态：把"此刻的处境"折算成偏好修正
    // ==========================================================

    /**
     * 这几条测试守的是「长期偏好与当前状态必须分开」（计划书第七节）。
     *
     * <p>同一个用户、同一份画像，只因为说了句"我累了"，结果就应该不一样——
     * 而且画像本身<b>不能被改动</b>：用户说"我饿了"不代表他从此变成美食爱好者。
     */

    @Test
    @DisplayName("说「我累了」→ 费腿的地方被压下去")
    void tiredStateDeprioritisesWalkingHeavyPlaces() {
        // 用中性画像（每个维度都 50）。⚠️ 刻意不用"只在乎走路"那种极端画像：
        // 那种情况下两个地点只在一个维度上有差异，任何调整都改变不了相对顺序，
        // 测不出状态有没有生效。中性画像才是常态——旅行测试答"说不好"就是全 50。
        Map<TravelDimension, Integer> pref = neutralPreference();

        PlaceCandidate flat = place("平地公园", traits(60, 50, 50, 50, 50, 50, 10));
        PlaceCandidate hilly = place("爬山路线", traits(60, 50, 50, 50, 50, 50, 95));

        assertEquals("爬山路线", engine.recommend(pref, List.of(flat, hilly), RELAXED, 3)
                .get(0).place().name(), "没状态时，属性更突出的爬山排前面");

        List<ScoredPlace> afterTired = engine.recommend(
                pref, List.of(flat, hilly), withStates(TravelState.TIRED), 3);
        assertEquals("平地公园", afterTired.get(0).place().name(),
                "累了之后平地应该排到前面——这就是「我累了」生效的方式");
    }

    @Test
    @DisplayName("说「我饿了」→ 有美食属性的地方冒到前面")
    void hungryStatePromotesFoodPlaces() {
        Map<TravelDimension, Integer> pref = neutralPreference();

        // 公园在其它维度上明显更好，所以没状态时它稳赢；
        // 面馆唯一的强项是美食——这样"饿了"才是翻盘的那个因素，而不是本来就在赢
        PlaceCandidate park = place("湿地公园", traits(90, 60, 5, 60, 60, 60, 60));
        PlaceCandidate restaurant = place("老字号面馆", traits(10, 40, 95, 40, 40, 40, 40));

        assertEquals("湿地公园", engine.recommend(pref, List.of(park, restaurant), RELAXED, 3)
                .get(0).place().name(), "没状态时，综合更好的公园应该排前面");

        List<ScoredPlace> afterHungry = engine.recommend(
                pref, List.of(park, restaurant), withStates(TravelState.HUNGRY), 3);
        assertEquals("老字号面馆", afterHungry.get(0).place().name(),
                "饿了之后馆子应该排到前面");
    }

    @Test
    @DisplayName("状态只作用于这一次打分，不会改动传进来的偏好")
    void statesDoNotMutateThePreference() {
        Map<TravelDimension, Integer> pref = neutralPreference();
        Map<TravelDimension, Integer> snapshot = new EnumMap<>(pref);

        engine.recommend(pref, List.of(place("甲", traits(50, 50, 50, 50, 50, 50, 50))),
                withStates(TravelState.TIRED), 3);

        assertEquals(snapshot, pref,
                "传给引擎的偏好不能被改动——「我累了」是关于地点的判断，不是对用户偏好的修改");
    }

    @Test
    @DisplayName("同时说「我累了」和「我想散步」→ 两个偏向抵消，结果回到中性")
    void multipleStatesCancelOut() {
        // 两个状态都作用于 walking：-0.8 和 +0.8，净效果是 0
        Map<TravelDimension, Integer> pref = neutralPreference();
        PlaceCandidate flat = place("平地公园", traits(60, 50, 50, 50, 50, 50, 10));
        PlaceCandidate hilly = place("爬山路线", traits(60, 50, 50, 50, 50, 50, 95));

        List<ScoredPlace> result = engine.recommend(pref, List.of(flat, hilly),
                withStates(TravelState.TIRED, TravelState.WANT_WALK), 3);

        assertEquals("爬山路线", result.get(0).place().name(),
                "一正一负抵消后，应该和无状态时一样");
    }

    // ==========================================================
    // 打分的拆解：四个因子必须能还原出最终分
    // ==========================================================

    /**
     * <b>这条是不变量测试，防的是"暴露出去的拆解和实际打分对不上"。</b>
     *
     * <p>前端要靠这四个因子解释"为什么是它"，还要把它们画成
     * {@code 兴趣 × 距离 × 质量 × 状态 = 最终分}。任何一个因子在传递过程中
     * 丢了或者算错了，用户看到的就是一本假账——比不给拆解更糟。
     */
    @Test
    @DisplayName("四个因子相乘必须精确等于 score——拆解不能是假账")
    void scoreEqualsProductOfItsFactors() {
        RecommendationContext ctx = RecommendationContext.withLocation(
                LocalTime.of(18, 0), 600, 30.2420, 120.1400, 10.0,
                100, java.util.Set.of(TravelState.HUNGRY));

        List<PlaceCandidate> places = List.of(
                placeAt("近的", 30.2450, 120.1420, traits(90, 60, 95, 70, 50, 50, 40)),
                placeAt("远的", 30.2900, 120.1800, traits(80, 70, 85, 60, 40, 60, 30)));

        for (ScoredPlace scored : engine.recommend(neutralPreference(), places, ctx, 3)) {
            double product = scored.interestScore() * scored.distanceFactor()
                    * scored.qualityFactor() * scored.stateFactor() * scored.weatherFactor();
            assertEquals(scored.score(), product, 1e-9,
                    scored.place().name() + " 的拆解对不上：五个因子相乘 = " + product
                            + "，但 score = " + scored.score());
        }
    }

    @Test
    @DisplayName("没有定位、没有状态、没有天气时，对应的因子恒为 1.0（乘法单位元）")
    void neutralFactorsAreExactlyOne() {
        List<ScoredPlace> top = engine.recommend(neutralPreference(),
                List.of(place("甲", traits(50, 50, 50, 50, 50, 50, 50))), RELAXED, 3);

        assertEquals(1.0, top.get(0).distanceFactor(),
                "没定位时距离因素应该完全失效，而不是给个 0.9");
        assertEquals(1.0, top.get(0).stateFactor(), "没状态时状态因素不该有任何影响");

        // ⚠️ 这条是"接天气"这件事不影响既有行为的关键。
        // 必须是**精确的** 1.0——不是"约等于"。
        // 整个测试套件（一百多个用例）走的都是这条路径：没有天气 → 打分数值
        // 与接天气之前完全一致。哪天它变成了 0.999，说明"拿不到天气"
        // 被当成了一种天气，而不是"这一项不存在"。
        assertEquals(1.0, top.get(0).weatherFactor(),
                "没有天气时天气因素必须是精确的 1.0，否则所有既有分数都会漂移");
    }

    @Test
    @DisplayName("状态修正会体现在拆解里——累了的时候 stateFactor 明显小于 1")
    void stateFactorShowsUpInTheBreakdown() {
        PlaceCandidate hilly = place("爬山路线", traits(50, 50, 50, 50, 50, 50, 95));

        ScoredPlace normal = engine.recommend(neutralPreference(), List.of(hilly), RELAXED, 3).get(0);
        ScoredPlace tired = engine.recommend(neutralPreference(), List.of(hilly),
                withStates(TravelState.TIRED), 3).get(0);

        assertEquals(1.0, normal.stateFactor());
        assertTrue(tired.stateFactor() < 0.5,
                "费腿的地方在'累了'的时候应该被压得很低，实际：" + tired.stateFactor());
    }

    @Test
    @DisplayName("状态系数永远落在 [0, 1]——score 那两条 CHECK 都不能被打破")
    void stateFactorNeverExceedsOne() {
        // 这是防数据库 CHECK 约束的：score = 五个因子相乘。
        // 系数 > 1 会让 score 超过上界；系数 < 0 会让 score 变成负数——
        // 两条都会让存库直接失败、接口 500。
        PlaceTraits mostFood = traits(0, 0, 100, 0, 0, 0, 0);
        PlaceTraits mostWalking = traits(0, 0, 0, 0, 0, 0, 100);

        assertTrue(RecommendationEngine.stateFactor(
                java.util.Set.of(TravelState.HUNGRY), Map.of(), mostFood) <= 1.0);
        assertTrue(RecommendationEngine.stateFactor(
                java.util.Set.of(TravelState.WANT_WALK), Map.of(), mostWalking) <= 1.0);
        assertEquals(1.0, RecommendationEngine.stateFactor(java.util.Set.of(), Map.of(), mostFood),
                "没有状态时应该是乘法单位元");
    }

    // ==========================================================
    // ⚠️ 原始权重（自然语言解析出来的）的限幅
    // ==========================================================

    /**
     * 这一组守的是**放开原始权重之后最容易出事的那条路**。
     *
     * <p>偏向只要低于 -1，打分系数就是**负数**，分数跟着变负，
     * 直接撞上数据库的 {@code CHECK (score BETWEEN 0 AND 1)}。
     *
     * <p>在接入自然语言之前这件事是"侥幸安全"的——词表里的值都在 ±0.8，
     * 而且恰好没有两个状态影响同一个维度。AI 能直接给权重之后，
     * {@code -0.8 + (-0.9) = -1.7} 随手就能造出来，所以引擎显式夹住了。
     */

    @Test
    @DisplayName("【核心】极端权重也不能算出负分——这是数据库 CHECK 的唯一防线")
    void extremeBiasNeverProducesNegativeFactor() {
        PlaceTraits maxed = traits(100, 100, 100, 100, 100, 100, 100);

        // 故意给远超合理范围的值，包括能凑出"求和后远低于 -1"的组合
        double[] extreme = {-5.0, -2.0, -1.5, -1.0, -0.99, 0.0, 1.0, 5.0, 100.0};

        for (TravelDimension dimension : TravelDimension.values()) {
            if (!dimension.affectsPlaceChoice()) {
                continue;
            }
            for (double value : extreme) {
                double factor = RecommendationEngine.stateFactor(
                        Set.of(), Map.of(dimension, value), maxed);

                assertTrue(factor >= 0.0,
                        String.format("%s=%.1f 时系数变成了负数：%.4f——"
                                + "这会让 score 变负，撞上 CHECK (score BETWEEN 0 AND 1)",
                                dimension, value, factor));
                assertTrue(factor <= 1.0,
                        String.format("%s=%.1f 时系数超过 1：%.4f", dimension, value, factor));
            }
        }
    }

    @Test
    @DisplayName("同一个维度上多个偏向会相加，但求和后仍然夹住")
    void summedBiasIsClampedToo() {
        PlaceTraits maxed = traits(100, 100, 100, 100, 100, 100, 100);

        // 命名状态和原始权重落在同一个维度上，且都是负的——加起来 -1.6
        double factor = RecommendationEngine.stateFactor(
                Set.of(TravelState.TIRED),                       // WALKING: -0.8
                Map.of(TravelDimension.WALKING, -0.8),           // 再来 -0.8
                maxed);

        assertTrue(factor >= 0.0, "求和后没夹住，算出了负数：" + factor);
    }

    @Test
    @DisplayName("非法的权重（NaN / 无穷）当作没影响，而不是把整个打分毁掉")
    void nonFiniteBiasIsTreatedAsNoEffect() {
        // 这些值理论上到不了这里（解析层会挡），但真到了也不该让
        // 整个推荐挂掉——NaN 一旦混进分数，排序会变成完全随机的结果
        PlaceTraits maxed = traits(100, 100, 100, 100, 100, 100, 100);

        assertEquals(1.0, RecommendationEngine.stateFactor(
                Set.of(), Map.of(TravelDimension.NATURE, Double.NaN), maxed), 1e-9);
    }

    @Test
    @DisplayName("原始权重真的在起作用——不是被夹成 0 就完事了")
    void extraBiasActuallyAffectsTheRanking() {
        // 限幅不能把功能限没了。给"热闹"一个负偏向，人多的那个应该被压下去。
        PlaceTraits quiet = traits(50, 50, 50, 50, 50, 10, 50);
        PlaceTraits busy = traits(50, 50, 50, 50, 50, 95, 50);

        double quietFactor = RecommendationEngine.stateFactor(
                Set.of(), Map.of(TravelDimension.CROWD_TOLERANCE, -0.8), quiet);
        double busyFactor = RecommendationEngine.stateFactor(
                Set.of(), Map.of(TravelDimension.CROWD_TOLERANCE, -0.8), busy);

        assertTrue(busyFactor < quietFactor,
                "给了'想安静'之后，热闹的地方该被压得更狠："
                        + busyFactor + " vs " + quietFactor);
        assertTrue(quietFactor > 0.9, "本来就不吵的地方不该被误伤：" + quietFactor);
    }

    @Test
    @DisplayName("新加的词表值确实有效——「想安静点」压住人多的")
    void newQuietStateWorks() {
        PlaceTraits busy = traits(50, 50, 50, 50, 50, 95, 50);

        double normal = RecommendationEngine.stateFactor(Set.of(), Map.of(), busy);
        double quiet = RecommendationEngine.stateFactor(Set.of(TravelState.QUIET), Map.of(), busy);

        assertEquals(1.0, normal);
        assertTrue(quiet < 0.5, "「想安静点」应该把人多的压得很低，实际：" + quiet);
    }

    // ==========================================================
    // 当前状态：预算（硬约束，不是排序）
    // ==========================================================

    @Test
    @DisplayName("超出预算的地方被直接排除，而不是排在后面")
    void placesOverBudgetAreExcluded() {
        Map<TravelDimension, Integer> pref = only(TravelDimension.NATURE, 100);

        PlaceCandidate freePark = placeWithTicket("免费公园", traits(90, 30, 10, 50, 20, 20, 50), 0);
        PlaceCandidate expensive = placeWithTicket("贵景区", traits(95, 50, 20, 80, 30, 40, 60), 200);

        // 不设预算时，属性更好的贵景区排第一
        List<ScoredPlace> unlimited = engine.recommend(pref, List.of(freePark, expensive), RELAXED, 3);
        assertEquals("贵景区", unlimited.get(0).place().name());

        // 设 50 元上限后，贵景区应该被整个排除（不是降到第二名）
        RecommendationContext budget = withBudget(50);
        List<ScoredPlace> limited = engine.recommend(pref, List.of(freePark, expensive), budget, 3);
        assertEquals(1, limited.size(), "超预算的地方应该被排除，只剩免费那个");
        assertEquals("免费公园", limited.get(0).place().name());
    }

    @Test
    @DisplayName("预算上限为 0 表示「只能去免费的」，而不是「不限」")
    void zeroBudgetMeansFreeOnly() {
        Map<TravelDimension, Integer> pref = only(TravelDimension.NATURE, 100);

        PlaceCandidate freePark = placeWithTicket("免费公园", traits(90, 0, 0, 0, 0, 0, 0), 0);
        PlaceCandidate paid = placeWithTicket("收费公园", traits(90, 0, 0, 0, 0, 0, 0), 1);

        List<ScoredPlace> result = engine.recommend(pref, List.of(freePark, paid),
                withBudget(0), 3);

        assertEquals(1, result.size(), "上限 0 就该只剩免费的——它和不设上限是两回事");
        assertEquals("免费公园", result.get(0).place().name());
    }

    // ---------- 状态测试用的小工具 ----------

    private static RecommendationContext withStates(TravelState... states) {
        return RecommendationContext.withLocation(
                RELAXED.now(), RELAXED.remainingMinutes(), null, null,
                RecommendationContext.DEFAULT_MAX_DISTANCE_KM,
                null, java.util.Set.of(states));
    }

    private static RecommendationContext withBudget(int maxTicketPrice) {
        return RecommendationContext.withLocation(
                RELAXED.now(), RELAXED.remainingMinutes(), null, null,
                RecommendationContext.DEFAULT_MAX_DISTANCE_KM,
                maxTicketPrice, java.util.Set.of());
    }

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

        RecommendationContext ctx = RecommendationContext.withLocation(
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

        RecommendationContext ctx = RecommendationContext.withLocation(
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
    // 天气：只压户外的地方
    // ==========================================================

    /**
     * 这一组守的是「下雨天别去户外」这条常识能不能真的算进分里。
     *
     * <p>⚠️ 整个这一组都是纯计算，<b>不联网、不需要高德 key</b>——
     * 天气在这里是一段构造出来的数据，怎么拿到它（以及拿不到怎么办）
     * 是 {@code AmapWeatherProvider} 和 {@code WeatherService} 的事。
     */

    @Test
    @DisplayName("【核心】下雨天，户外的地方被压下去，室内的几乎不受影响")
    void rainSuppressesOutdoorPlacesButNotIndoorOnes() {
        Weather rain = Weather.of("小雨", 20);

        double outdoor = RecommendationEngine.weatherFactor(rain, 0);    // 完全户外
        double halfIndoor = RecommendationEngine.weatherFactor(rain, 50); // 一半室内
        double indoor = RecommendationEngine.weatherFactor(rain, 100);    // 全程室内

        assertEquals(0.5, outdoor, 1e-9, "完全户外的地点在雨天应该砍半");
        assertEquals(0.75, halfIndoor, 1e-9, "半室内的地方扣一半的惩罚");
        assertEquals(1.0, indoor, 1e-9, "全程室内的地方完全不该受影响");
    }

    @Test
    @DisplayName("【核心】下雨之后，室内博物馆反超户外景点")
    void rainFlipsTheRankingBetweenOutdoorAndIndoor() {
        // 两个地点除室内程度外完全一样——这样排序变化只可能来自天气
        PlaceTraits same = traits(80, 80, 50, 70, 40, 50, 50);
        PlaceCandidate outdoor = placeWithIndoor("露天景点", same, 0);
        PlaceCandidate indoor = placeWithIndoor("室内展馆", same, 95);

        RecommendationContext sunny = RELAXED.withWeather(Weather.of("晴", 22));
        RecommendationContext rainy = RELAXED.withWeather(Weather.of("中雨", 18));

        // 晴天：两者兴趣分相同，户外那个排在前面（同分时保持输入顺序）
        List<ScoredPlace> before = engine.recommend(
                neutralPreference(), List.of(outdoor, indoor), sunny, 2);
        assertEquals("露天景点", before.get(0).place().name(), "晴天时两者应该不相上下");

        // 下雨：室内那个反超
        List<ScoredPlace> after = engine.recommend(
                neutralPreference(), List.of(outdoor, indoor), rainy, 2);
        assertEquals("室内展馆", after.get(0).place().name(),
                "下雨之后室内的地方应该排到前面——这就是天气生效的方式");
    }

    @Test
    @DisplayName("好天气不改变任何东西（晴天/多云/阴天都不惩罚）")
    void goodWeatherChangesNothing() {
        PlaceCandidate outdoor = placeWithIndoor("户外", traits(50, 50, 50, 50, 50, 50, 50), 0);

        for (String condition : List.of("晴", "多云", "阴")) {
            assertEquals(1.0, RecommendationEngine.weatherFactor(Weather.of(condition, 22), 0),
                    condition + " 不该对户外地点有任何惩罚");
        }
    }

    @Test
    @DisplayName("认不出来的天气描述不惩罚——不确定的时候不要瞎猜")
    void unknownWeatherDoesNotPenalize() {
        // 高德有三十多种天气描述，将来还可能加新的。
        // 认不出来就当作"没有信息"，而不是按最坏情况处理。
        assertEquals(1.0, RecommendationEngine.weatherFactor(Weather.of("下开水", 25), 0));
    }

    @Test
    @DisplayName("高温也压户外，但比下雨轻")
    void extremeHeatPenalizesOutdoorButLessThanRain() {
        double hot = RecommendationEngine.weatherFactor(Weather.of("晴", 38), 0);
        double rain = RecommendationEngine.weatherFactor(Weather.of("小雨", 20), 0);

        assertTrue(hot < 1.0, "38 度还推荐全程户外的地方是不合理的");
        assertTrue(hot > rain,
                "高温的惩罚应该比下雨轻——热还能忍，淋湿不能。高温 " + hot + " vs 雨 " + rain);
    }

    @Test
    @DisplayName("weatherFactor 永远 ≤ 1——score ≤ 1 这条不变量不能被打破")
    void weatherFactorNeverExceedsOne() {
        // 数据库上有 CHECK (score BETWEEN 0 AND 1)，而 score 是五个因子相乘。
        // 只要 weatherFactor 超过 1，存库时接口就会 500。
        //
        // 这里把"最坏情况"都试一遍：各种天气 × 各个室内程度 × 极端温度。
        for (String condition : List.of("晴", "多云", "阴", "小雨", "暴雨", "雷阵雨伴有冰雹",
                "小雪", "暴雪", "雾", "强沙尘暴", "下开水")) {
            for (double temperature : List.of(-20.0, 0.0, 22.0, 35.0, 45.0)) {
                for (int indoor = 0; indoor <= 100; indoor += 25) {
                    double factor = RecommendationEngine.weatherFactor(
                            Weather.of(condition, temperature), indoor);
                    assertTrue(factor <= 1.0,
                            String.format("weatherFactor 超过 1 了：%s %.0f° indoor=%d → %.4f",
                                    condition, temperature, indoor, factor));
                    assertTrue(factor >= 0.0,
                            "weatherFactor 不该是负数：" + factor);
                }
            }
        }
    }

    @Test
    @DisplayName("天气修正会体现在拆解里——雨天户外地点的 weatherFactor 明显小于 1")
    void weatherFactorShowsUpInTheBreakdown() {
        PlaceCandidate seaside = placeWithIndoor("海边栈道",
                traits(80, 50, 50, 70, 40, 50, 50), 0);

        ScoredPlace sunny = engine.recommend(neutralPreference(), List.of(seaside),
                RELAXED.withWeather(Weather.of("晴", 22)), 3).get(0);
        ScoredPlace rainy = engine.recommend(neutralPreference(), List.of(seaside),
                RELAXED.withWeather(Weather.of("大雨", 18)), 3).get(0);

        assertEquals(1.0, sunny.weatherFactor(), "晴天不该有任何影响");
        assertTrue(rainy.weatherFactor() <= 0.5,
                "暴雨里的户外地点应该被压得很低，实际：" + rainy.weatherFactor());
        assertTrue(rainy.score() < sunny.score(), "雨天分数应该更低");
    }

    // ==========================================================
    // 测试夹具
    // ==========================================================

    /**
     * 中性画像：所有维度都是 50，也就是旅行测试里"说不好"全选的那个结果。
     *
     * <p>状态类的测试用它而不是 {@code only(...)}：极端画像（只有一个维度有权重）
     * 下，两个地点往往只在一个维度上有差异，任何调整都改变不了它们的相对顺序，
     * 测不出状态到底有没有生效。
     */
    private static Map<TravelDimension, Integer> neutralPreference() {
        Map<TravelDimension, Integer> map = new EnumMap<>(TravelDimension.class);
        for (TravelDimension dimension : TravelDimension.values()) {
            map.put(dimension, 50);
        }
        return map;
    }

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
                OUTDOOR, 80, 0, suggestedMinutes, null, null, "测试地点");
    }

    private static PlaceCandidate place(String name, PlaceTraits traits,
                                        LocalTime openFrom, LocalTime openTo) {
        return new PlaceCandidate(1L, name, "MUSEUM", 30.2420, 120.1400, traits,
                OUTDOOR, 80, 0, 60, openFrom, openTo, "测试地点");
    }

    private static PlaceCandidate placeAt(String name, double lat, double lon, PlaceTraits traits) {
        return new PlaceCandidate(1L, name, "NATURE", lat, lon, traits,
                OUTDOOR, 80, 0, 60, null, null, "测试地点");
    }

    private static PlaceCandidate placeWithQuality(String name, PlaceTraits traits, int quality) {
        return new PlaceCandidate(1L, name, "NATURE", 30.2420, 120.1400, traits,
                OUTDOOR, quality, 0, 60, null, null, "测试地点");
    }

    /** 指定门票价格的地点。用来验证"预算上限"这条硬约束。 */
    private static PlaceCandidate placeWithTicket(String name, PlaceTraits traits, int ticketPrice) {
        return new PlaceCandidate(1L, name, "NATURE", 30.2420, 120.1400, traits,
                OUTDOOR, 80, ticketPrice, 60, null, null, "测试地点");
    }

    /**
     * 带室内程度的地点，用来测天气。
     *
     * <p>{@code indoor}：0 = 完全户外，100 = 全程室内。
     */
    private static PlaceCandidate placeWithIndoor(String name, PlaceTraits traits, int indoor) {
        return new PlaceCandidate(1L, name, "NATURE", 30.2420, 120.1400, traits,
                indoor, 80, 0, 60, null, null, "测试地点");
    }
}
