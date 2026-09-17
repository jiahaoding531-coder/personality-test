package com.example.personality.ai;

import com.example.personality.domain.TravelDimension;
import com.example.personality.domain.TravelState;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把用户打的一句话翻译成系统听得懂的结构化输入。
 *
 * <h2>⚠️ AI 只做理解，不做打分</h2>
 *
 * <p>这个对象里**没有分数、没有排序、没有系数**（{@code biases} 是个例外，
 * 而且它只是"往哪个方向偏一点"，真正的数值计算仍然由确定性算法做）。
 *
 * <p>这条边界从项目第一天就划着——计划书第七节：
 * <blockquote>AI 负责理解、总结和解释；传统算法负责距离、时间、营业状态和数值排序。</blockquote>
 *
 * <p>理解归理解，排序归排序。这样推荐结果才是可复现、可解释、可测试的。
 *
 * @param states           能对上词表的部分，比如"我累了" → {@code TIRED}
 * @param biases           对不上词表的部分，AI 直接给的原始维度偏向。
 *                         <p>比如"想找个特别小众的地方" → {@code {CROWD_TOLERANCE: -0.5, HIDDEN_GEMS: 0.6}}。
 *                         <p>⚠️ 取值范围由引擎按维度求和后夹到 [-1, 1]，
 *                         这里**不保证**在范围内（解析时会顺手夹一次，引擎兜底）
 * @param remainingMinutes 用户提到的剩余时间（分钟）。没提就是 null
 * @param maxDistanceKm    用户提到的距离上限（公里）。没提就是 null
 * @param maxTicketPrice   用户提到的预算上限（元）。没提就是 null
 * @param unrecognized     <b>听懂了但用不上的部分</b>，原样带回给用户看。
 *                         <p>比如"想找个能带狗的地方"——我们的地点数据里没有这个维度。
 *                         <p>⚠️ 这一项是这个功能的诚实所在：说不出来就说"这句我没用上"，
 *                         比假装听懂强。收集起来显示给用户，他才知道系统的边界在哪
 * @param summary          一句话复述，给用户确认"我理解成什么了"
 */
public record TextIntentResult(
        Set<TravelState> states,
        Map<TravelDimension, Double> biases,
        Integer remainingMinutes,
        Double maxDistanceKm,
        Integer maxTicketPrice,
        List<String> unrecognized,
        String summary,
        List<TravelIntentOperation> operations
) {

    /** 兼容旧测试和非 operations 调用方，过渡期内保留。 */
    public TextIntentResult(
            Set<TravelState> states,
            Map<TravelDimension, Double> biases,
            Integer remainingMinutes,
            Double maxDistanceKm,
            Integer maxTicketPrice,
            List<String> unrecognized,
            String summary
    ) {
        this(states, biases, remainingMinutes, maxDistanceKm, maxTicketPrice,
                unrecognized, summary, List.of());
    }

    /** 什么都没解析出来。 */
    public static TextIntentResult empty() {
        return new TextIntentResult(Set.of(), Map.of(), null, null, null, List.of(), "", List.of());
    }

    /** 有没有解析出任何能用的东西。全空时前端该提示用户换个说法。 */
    public boolean hasAnythingUsable() {
        return !operations.isEmpty() || !states.isEmpty() || !biases.isEmpty()
                || remainingMinutes != null || maxDistanceKm != null || maxTicketPrice != null;
    }
}
