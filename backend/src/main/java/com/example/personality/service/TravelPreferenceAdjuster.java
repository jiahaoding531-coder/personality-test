package com.example.personality.service;

import com.example.personality.domain.PlaceTraits;
import com.example.personality.domain.TravelDimension;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 把用户对推荐的反馈折算成对旅行画像的修正——「回写画像」那一步。
 *
 * <h2>为什么是"实时算"而不是"存一张修正量表"</h2>
 *
 * <p>最直觉的做法是收到 👎 就 UPDATE 一下画像分数，或者单开一张表存修正量。
 * 这两种都不好：
 * <ul>
 *   <li><b>直接改画像</b>会让「用户答了 8 道题得出的画像」和「被反馈改过的画像」
 *       混成一份，将来想分析"问卷准不准"时无法还原</li>
 *   <li><b>存修正量表</b>则要处理"用户把 👎 改成 👍 怎么撤销"这类问题，
 *       多一份状态就多一类不一致</li>
 * </ul>
 *
 * <p>这个类的做法是：<b>{@code travel_profiles} 一个字节都不动</b>，
 * 每次要用的时候，拿问卷画像 + 这一会话的全部反馈<b>重新算一遍</b>。
 * 反馈原文（{@code recommendation_feedback}）就是唯一的事实来源，
 * 用户改主意时符号一变，重算的结果天然就是对的——不需要"撤销"。
 *
 * <h2>这个类是纯逻辑的</h2>
 *
 * <p>和 {@code ScoringService} / {@code RecommendationEngine} 一样：
 * 不碰数据库、不碰 Spring 容器（{@code @Service} 只是注册标签），
 * 可以直接 {@code new} 出来喂构造好的数据做单测。
 *
 * <h2>⚠️ 归因是启发式的，有已知局限</h2>
 *
 * <p>一次 👎 是针对<b>一个地点</b>的，但一个地点有 7 个属性维度。
 * 用户不满意可能是因为它太吵、太远、东西不好吃，也可能只是因为当天心情不好。
 * 而这里只能猜："这次推荐里哪个维度的贡献最大，就调哪个"。
 *
 * <p>后果：如果一个地方因为"人太多"被否掉，但它的主导维度是「自然风光」，
 * 那用户对自然风光的偏好会被误降。要真正准确，得让用户说出原因
 * （比如长按 👎 弹出维度选择），那是更重的交互，不在这一步。
 */
@Service
public class TravelPreferenceAdjuster {

    /**
     * 单条反馈对画像的调整幅度。
     *
     * <p>取 10 而不是更大：旅行画像的分数只能落在 0/25/50/75/100 五档，
     * 一档间距 25。10 大约是半档——一次反馈能看出方向，但不至于一次就翻盘。
     * 连着三次同方向的反馈才会移动超过一档。
     */
    static final int STEP = 10;

    /**
     * 同方向累计修正的上限（绝对值）。
     *
     * <p>防止"用户连续否掉 8 个自然景点"把自然风光一路压到 0——
     * 那已经不是"修正"而是"用几次点击覆盖掉整份问卷"了。
     * 40 分大约是 1.5 档，够表达倾向，又保留问卷的基本盘。
     */
    static final int MAX_TOTAL_DELTA = 40;

    /**
     * 计算这次推荐该用的「有效偏好」。
     *
     * @param questionnaire 问卷算出来的原始画像（0~100），不会被修改
     * @param signals       这个会话收到的全部反馈信号，没有就传空列表
     * @return 有效偏好，可以直接喂给 {@code RecommendationEngine}
     */
    public Map<TravelDimension, Integer> effectivePreference(
            Map<TravelDimension, Integer> questionnaire, List<FeedbackSignal> signals) {

        Map<TravelDimension, Integer> deltas = totalDeltas(signals);

        Map<TravelDimension, Integer> effective = new EnumMap<>(TravelDimension.class);
        for (TravelDimension dimension : TravelDimension.values()) {
            int base = questionnaire.getOrDefault(dimension, 0);
            int delta = deltas.getOrDefault(dimension, 0);
            effective.put(dimension, clamp(base + delta, 0, 100));
        }
        return effective;
    }

    /** 各维度的累计修正量（已按 {@link #MAX_TOTAL_DELTA} 夹住）。给响应展示用。 */
    public Map<TravelDimension, Integer> totalDeltas(List<FeedbackSignal> signals) {
        Map<TravelDimension, Integer> deltas = new EnumMap<>(TravelDimension.class);
        if (signals == null) {
            return deltas;
        }
        for (FeedbackSignal signal : signals) {
            // 不参与排序的维度不该被调整——不然会出现"提前规划"被反馈改动，
            // 但它对地点排序根本不起作用，等于白改
            if (signal.dimension() == null || !signal.dimension().affectsPlaceChoice()) {
                continue;
            }
            deltas.merge(signal.dimension(), signal.liked() ? STEP : -STEP, Integer::sum);
        }
        for (TravelDimension dimension : TravelDimension.values()) {
            Integer value = deltas.get(dimension);
            if (value != null) {
                deltas.put(dimension, clamp(value, -MAX_TOTAL_DELTA, MAX_TOTAL_DELTA));
            }
        }
        return deltas;
    }

    /**
     * 归因：这次推荐里，哪个维度的贡献最大。
     *
     * <p>算法和 {@code RecommendationEngine} 里的兴趣匹配一致——
     * 「用户偏好 × 地点属性」，取最大的那个。所以它和返回给前端的
     * {@code reasons[0]} 是同一个维度：<b>既然我们是靠这个维度推荐给用户的，
     * 用户说不喜欢，那就先调这个维度。</b>
     *
     * <p>用问卷画像（而不是已经修正过的有效画像）来算，是为了让归因结果稳定——
     * 否则同一条反馈在不同时刻会归到不同维度上，历史数据就没法解释了。
     *
     * @return 贡献最大的维度；所有维度贡献都是 0 时返回 null（比如用户问卷全填 0）
     */
    public TravelDimension dominantDimension(Map<TravelDimension, Integer> questionnaire, PlaceTraits traits) {
        TravelDimension best = null;
        int bestContribution = 0;

        for (TravelDimension dimension : TravelDimension.values()) {
            if (!dimension.affectsPlaceChoice()) {
                continue;
            }
            int contribution = questionnaire.getOrDefault(dimension, 0) * traits.valueOf(dimension);
            if (contribution > bestContribution) {
                bestContribution = contribution;
                best = dimension;
            }
        }
        return best;
    }

    /** 从"主维度"复原成"该维度累计调整了多少"，用来解释画像为什么变了。 */
    public List<TravelDimension> adjustedDimensions(List<FeedbackSignal> signals) {
        Map<TravelDimension, Integer> deltas = totalDeltas(signals);
        List<TravelDimension> adjusted = new ArrayList<>();
        for (TravelDimension dimension : TravelDimension.values()) {
            if (deltas.getOrDefault(dimension, 0) != 0) {
                adjusted.add(dimension);
            }
        }
        return adjusted;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 一条反馈折算出的信号。
     *
     * @param dimension 这条反馈归因到哪个维度
     * @param liked     true = 👍（上调），false = 👎（下调）
     */
    public record FeedbackSignal(TravelDimension dimension, boolean liked) {
    }
}
