package com.example.personality.service;

import com.example.personality.domain.PlaceCandidate;
import com.example.personality.domain.PlaceTraits;
import com.example.personality.domain.RecommendationContext;
import com.example.personality.domain.ScoredPlace;
import com.example.personality.domain.TravelDimension;
import com.example.personality.domain.TravelState;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * 旅行推荐引擎：把「用户想要什么」和「地点能给什么」对上。
 *
 * <h2>为什么这是个纯逻辑类</h2>
 *
 * <p>和 {@code ScoringService} 一样——不碰数据库、不碰 HTTP、不碰 Spring
 * （{@code @Service} 只是注册标签）。输入是纯数据记录，输出是排序结果。
 *
 * <p>这样切分对这个模块尤其重要：<b>推荐效果好不好是个算法问题，
 * 不该和"数据库取数对不对""接口返回格式对不对"混在一起排查。</b>
 *
 * <h2>算法</h2>
 *
 * <pre>
 *   ① 硬过滤：不营业的、时间不够的，直接排除
 *   ② 兴趣匹配（主信号，占大头）
 *   ③ 乘以距离衰减
 *   ④ 乘以质量修正（微调）
 * </pre>
 *
 * <p><b>为什么用「相乘」而不是「加权求和」？</b>
 * 相乘意味着每个因子都必须是"不差"的，任何一项掉到 0 分整个结果就是 0。
 * 这符合直觉：一个地方再符合你的口味，如果太远或者快关门了，
 * 也不该被推荐。加权求和则会让"兴趣极高"补偿掉"距离极远"，
 * 推出一个你根本去不了的地方。
 *
 * <p>计划书第七节的产品原则说得很清楚：
 * <blockquote>AI 负责理解、总结和解释；传统算法负责距离、时间、营业状态和数值排序。</blockquote>
 * 这个类就是那个"传统算法"的部分——**完全确定性，可测试，不花一分钱**。
 */
@Service
public class RecommendationEngine {

    /**
     * 距离衰减的尺度参数（公里）。
     *
     * <p>用双曲衰减 {@code 1/(1 + d/4)} 而不是线性衰减：
     * <ul>
     *   <li>0 公里 → 1.00</li>
     *   <li>2 公里 → 0.67</li>
     *   <li>4 公里 → 0.50</li>
     *   <li>10 公里 → 0.29</li>
     * </ul>
     *
     * <p>线性衰减的话，8 公里的地方得分只有 1.6 公里的 20%——
     * 太狠了。对旅行者来说，多走 6 公里远不至于让一个地方的吸引力
     * 掉到五分之一。双曲衰减更贴近真实感受，而且永远不会降到 0，
     * 不会出现"因为远了 100 米就被完全排除"的荒谬结果。
     */
    private static final double DISTANCE_SCALE_KM = 4.0;

    /**
     * 质量分最多往下扣多少。取值 0.15 ⇒ qualityFactor 落在 0.85 ~ 1.00。
     *
     * <p><b>这个系数决定了 score 的上界。</b>
     * interest 和 distanceFactor 都 ≤ 1，所以只要 qualityFactor 也 ≤ 1，
     * score 就<b>必然 ≤ 1</b>——这正是数据库
     * {@code ck_recommendations_score CHECK (score BETWEEN 0 AND 1)} 要求的不变量，
     * 也是 {@link ScoredPlace#scorePercent()} 不会显示成 "104%" 的前提。
     *
     * <p><b>⚠️ 别写回 {@code 1 + W × (q/100)} 那种"上下浮动"的形式。</b>
     * 那样上界是 1.15：一个 quality=92 又离得近的地方（西湖·苏堤就是）
     * 算出来 1.04，一存库就违反 CHECK 约束，接口直接 500。
     * 质量分的语义是"这家店靠不靠谱"，只应该往下扣，不该往上加。
     */
    private static final double QUALITY_WEIGHT = 0.15;

    /** 解释里最多列出几个维度。 */
    private static final int MAX_MATCHES_IN_REASON = 3;

    /**
     * 给候选地点打分排序，返回前 N 个。
     *
     * @param preference 用户的旅行偏好（8 个维度，0~100）
     * @param candidates 候选地点
     * @param context    当前处境（时间、剩余时长、定位）
     * @param limit      要几个（通常是 3）
     */
    public List<ScoredPlace> recommend(Map<TravelDimension, Integer> preference,
                                       List<PlaceCandidate> candidates,
                                       RecommendationContext context,
                                       int limit) {

        List<ScoredPlace> scored = new ArrayList<>();

        for (PlaceCandidate place : candidates) {
            // ---------- ① 硬过滤 ----------
            // 关门的地方不推荐。理由不是"分数低"而是"去了也没用"，
            // 所以是排除而不是降权——降权的话它照样可能挤进 Top 3。
            if (!place.isOpenAt(context.now())) {
                continue;
            }
            // 时间不够的地方也不推荐。用户说还剩 2 小时，
            // 推一个需要 3 小时的地方等于让他半途而废。
            if (place.suggestedMinutes() > context.remainingMinutes()) {
                continue;
            }
            // 超出预算的地方也不推荐。用户说"预算不多"，推一个 200 块门票的
            // 地方给他，和"时间不够"是同一类错误：去了也没用。
            if (!context.allowsTicketPrice(place.ticketPrice())) {
                continue;
            }

            // ---------- ② 兴趣匹配（主信号） ----------
            MatchResult match = computeInterest(preference, place.traits());

            // ---------- ③ 距离 ----------
            Double distanceKm = null;
            double distanceFactor = 1.0;
            if (context.hasLocation()) {
                distanceKm = haversineKm(
                        context.latitude(), context.longitude(),
                        place.latitude(), place.longitude());
                if (distanceKm > context.maxDistanceKm()) {
                    continue;   // 超出最大半径，同样属于硬过滤
                }
                distanceFactor = 1.0 / (1.0 + distanceKm / DISTANCE_SCALE_KM);
            }

            // ---------- ④ 质量微调 ----------
            // quality=100 → 1.00（不扣），quality=0 → 0.85（扣 15%）
            double qualityFactor = 1.0 - QUALITY_WEIGHT * (1.0 - place.quality() / 100.0);

            // ---------- ⑤ 此刻的状态 ----------
            // "我累了"就是在这里生效的：越费腿的地方这个系数越小。
            // 没状态时恒等于 1.0，几乎零开销。
            double stateFactor = stateFactor(context.states(), place.traits());

            double score = match.interest() * distanceFactor * qualityFactor * stateFactor;

            scored.add(new ScoredPlace(place, score, match.interest(),
                    distanceKm, qualityFactor, match.topMatches()));
        }

        scored.sort(Comparator.comparingDouble(ScoredPlace::score).reversed());
        return scored.size() > limit ? scored.subList(0, limit) : scored;
    }

    // ==========================================================
    // 内部实现
    // ==========================================================

    /**
     * 「此刻的状态」折算成的一个乘性系数。
     *
     * <p><b>⚠️ 关键：状态作用于地点属性，不是用户偏好。</b>
     *
     * <p>最初把"我累了"实现成"把步行意愿从 100 降到 60"，测试直接红了——
     * 排序一点没变。因为兴趣分是归一化的加权平均，把某个维度的权重调小，
     * 分子分母同时缩小，比值不变。而且语义上就错了：
     * <b>"我累了"不是"我没那么在乎走路了"，而是"费腿的地方要变差"。</b>
     * 前者是关于「你」的，后者是关于「地点」的。
     *
     * <p>公式（偏向 b，地点在该维度的得分 v）：
     * <pre>
     *   系数 = Π (1 + b × v/100) / (1 + max(b, 0))
     * </pre>
     *
     * <p>除以 {@code (1 + max(b, 0))} 是<b>必须的归一化</b>：不除的话正偏向
     * 会让系数大于 1，总分就可能超过 1——而数据库上有
     * {@code CHECK (score BETWEEN 0 AND 1)}。除了之后所有系数都 ≤ 1，
     * 和距离系数、质量系数保持一致，score ≤ 1 这条不变量继续成立。
     *
     * <p>没有状态时直接返回 1.0（乘法单位元），对原有打分零影响。
     */
    static double stateFactor(Set<TravelState> states, PlaceTraits traits) {
        if (states == null || states.isEmpty()) {
            return 1.0;
        }

        // 多个状态可能作用于同一个维度（比如"累了"和"想散步"都影响 walking），
        // 偏向直接相加——两个相反的偏向会互相抵消，这是对的
        Map<TravelDimension, Double> bias = new EnumMap<>(TravelDimension.class);
        for (TravelState state : states) {
            state.attributeBias().forEach((dimension, value) -> bias.merge(dimension, value, Double::sum));
        }

        double factor = 1.0;
        for (Map.Entry<TravelDimension, Double> entry : bias.entrySet()) {
            double b = entry.getValue();
            double placeValue = traits.valueOf(entry.getKey()) / 100.0;
            factor *= (1.0 + b * placeValue) / (1.0 + Math.max(b, 0.0));
        }
        return factor;
    }

    /**
     * 计算兴趣匹配度。
     *
     * <p>用的是「以用户偏好为权重的加权平均」：
     * <pre>
     *   interest = Σ(用户偏好[d] × 地点属性[d]) / Σ(用户偏好[d] × 100)
     * </pre>
     *
     * <p><b>为什么不是简单的点积？</b>点积会让"各方面都还行"的地方
     * 压过"在某一方面特别突出"的地方。除以 {@code Σ(偏好 × 100)}
     * 做了归一化，得到的是"这个地点在**你在乎的维度上**平均拿了几分"。
     *
     * <p>举例：一个用户只在乎自然（NATURE=100，其它=0），
     * 那么 interest 就等于该地点的 nature 属性除以 100。
     * 一个 nature=95 的地方得 0.95，nature=20 的地方得 0.20——
     * 完全由他在乎的那个维度决定，不受其它维度干扰。
     *
     * <p><b>用户全填最低分的情况</b>：所有偏好都是 0，分母为 0。
     * 这时退化成"所有维度等权"——因为"什么都不想要"没法用来加权，
     * 与其报错不如给一个中性的排序。
     */
    private MatchResult computeInterest(Map<TravelDimension, Integer> preference, PlaceTraits traits) {
        double weightedSum = 0;
        double maxPossible = 0;
        List<ScoredPlace.MatchedDimension> contributions = new ArrayList<>();

        for (TravelDimension dimension : TravelDimension.values()) {
            if (!dimension.affectsPlaceChoice()) {
                continue;   // PLANNING 不参与地点排序
            }
            int userPref = preference.getOrDefault(dimension, 0);
            int placeValue = traits.valueOf(dimension);

            double contribution = (double) userPref * placeValue;
            weightedSum += contribution;
            maxPossible += (double) userPref * 100;

            if (userPref > 0) {
                contributions.add(new ScoredPlace.MatchedDimension(
                        dimension, userPref, placeValue, contribution));
            }
        }

        double interest;
        if (maxPossible == 0) {
            // 退化成等权平均
            interest = equalWeightAverage(traits);
        } else {
            interest = weightedSum / maxPossible;
        }

        // 按贡献从大到小排，取前几个用来解释"为什么推荐这个"
        contributions.sort(Comparator.comparingDouble(
                ScoredPlace.MatchedDimension::contribution).reversed());
        List<ScoredPlace.MatchedDimension> top =
                contributions.size() > MAX_MATCHES_IN_REASON
                        ? contributions.subList(0, MAX_MATCHES_IN_REASON)
                        : contributions;

        return new MatchResult(interest, List.copyOf(top));
    }

    /** 所有维度等权时的平均属性值。 */
    private double equalWeightAverage(PlaceTraits traits) {
        int sum = 0;
        int count = 0;
        for (TravelDimension dimension : TravelDimension.values()) {
            if (!dimension.affectsPlaceChoice()) {
                continue;
            }
            sum += traits.valueOf(dimension);
            count++;
        }
        return count == 0 ? 0 : (double) sum / count / 100.0;
    }

    /**
     * 两个经纬度之间的球面距离（公里），用 Haversine 公式。
     *
     * <p>为什么不用平面几何（勾股定理）？因为在经度方向上，
     * 1 度对应的实际距离随纬度变化——在杭州（北纬 30°）1 经度约 96 公里，
     * 而在赤道是 111 公里。平面计算在城市尺度（10 公里内）误差其实很小，
     * 但 Haversine 也就多几行，没必要省。
     *
     * <p>地球半径取 6371 公里（平均半径）。
     */
    static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        final double earthRadiusKm = 6371.0;

        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusKm * c;
    }

    /** 兴趣匹配的中间结果。 */
    private record MatchResult(double interest, List<ScoredPlace.MatchedDimension> topMatches) {
    }
}
