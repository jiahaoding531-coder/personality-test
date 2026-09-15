package com.example.personality.ai;

import com.example.personality.domain.ScoredPlace;
import com.example.personality.domain.TravelState;
import com.example.personality.domain.Weather;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/**
 * 交给大模型去写推荐理由的**全部事实**。
 *
 * <h2>这个 record 就是提示词的"数据边界"</h2>
 *
 * <p>看它一眼就知道 AI 能知道什么、不能知道什么。这在写"不许编造"这类约束时
 * 特别有用：模型能编出来的东西，一定是这里没给的东西。
 *
 * <p>⚠️ <b>这里面的每一个字段都是查得到、算得出来的事实</b>——
 * 没有一个是"我们猜的"。这一点值得守住：理由一旦沾上编造，
 * 用户按它做了决定却发现不对，整个助手的可信度就没了。
 *
 * <h2>数据从哪来</h2>
 *
 * <p>全部来自数据库，因为生成理由时和算推荐时是<b>两次请求</b>
 * （推荐要秒回，AI 要几秒）。五因子和处境在 V11 才被存下来——
 * 在那之前它们算完就丢了，AI 只能对着一个孤零零的百分数干瞪眼。
 *
 * @param locationLabel  用户在哪（"杭州市西湖区北山街附近"）。没配高德时为 null
 * @param now            算这批推荐时是几点
 * @param remainingMinutes 当时还剩多少可玩时间
 * @param maxDistanceKm  当时设的最远半径
 * @param maxTicketPrice 当时设的预算上限。null = 不限
 * @param states         当时生效的状态
 * @param inferredStates <b>其中哪些是系统推断的。</b>
 *                       提示词会把它单独标出来——"你说了你累了"和"系统猜你累了"
 *                       是两回事，转述时不能混
 * @param weather        当时的天气。没配高德或查不到时为 null
 * @param places         要解释的地点，按名次升序
 */
public record TravelReasonInput(
        String locationLabel,
        LocalTime now,
        int remainingMinutes,
        double maxDistanceKm,
        Integer maxTicketPrice,
        Set<TravelState> states,
        Set<TravelState> inferredStates,
        Weather weather,
        List<ExplainedPlace> places
) {

    /**
     * 一个待解释的地点。
     *
     * @param scorePercent 总分（0~100 的整数）
     * @param factors      五个因子。<b>这是"为什么是它"的硬事实</b>，
     *                     提示词要求模型的一切解释都要落在它们上面
     * @param ticketPrice  门票（元），0 = 免费
     * @param openFrom     营业开始（"HH:mm"）。<b>null 表示全天开放</b>
     * @param topMatches   这个地点在"你在乎的维度"上拿了多少分，按贡献降序。
     *                     <p>⚠️ 和前端「为什么是它」面板里显示的是<b>同一份</b>数据
     *                     （都由 {@code RecommendationEngine.computeInterest} 算出），
     *                     所以 AI 的话不会和下面的账对不上
     */
    public record ExplainedPlace(
            int rank,
            String name,
            String category,
            String description,
            int scorePercent,
            Factors factors,
            int ticketPrice,
            int suggestedMinutes,
            String openFrom,
            String openTo,
            List<ScoredPlace.MatchedDimension> topMatches
    ) {
    }

    /**
     * 五个打分因子，都是 0~1。
     *
     * <p>它们的关系是 {@code 总分 = 五者相乘}。这里刻意用 0~1 的原始比例
     * 而不是百分数：提示词要求模型<b>不要提数字</b>，给比例是让它理解
     * "哪一项拖了后腿"，而不是让它去复述。
     */
    public record Factors(
            double interest,
            double distance,
            double quality,
            double state,
            double weather
    ) {
    }
}
