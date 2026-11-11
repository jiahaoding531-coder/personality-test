package com.example.personality.ai;

import com.example.personality.domain.ScoredPlace;
import com.example.personality.domain.TravelDimension;
import com.example.personality.domain.TravelState;
import com.example.personality.domain.Weather;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 推荐批次可核验的数据载体。不是其中每个字段都允许模型使用。
 *
 * <h2>这个 record 就是提示词的"数据边界"</h2>
 *
 * <p>看它一眼就知道 AI 能知道什么、不能知道什么。这在写"不许编造"这类约束时
 * 特别有用：模型能编出来的东西，一定是这里没给的东西。
 *
 * <p>模型只能使用 {@link TravelReasonEvidence} 白名单目录：批次条件、保存评分、
 * 明确标明来源的当前静态资料；推测状态单独标识，未知值不作为肯定事实。
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
 * @param customBiases   用户说了词表覆盖不了的话时，AI 解析出来的原始维度偏向。
 *                       比如"想找个特别小众的地方" → {@code {CROWD_TOLERANCE: -0.5, HIDDEN_GEMS: 0.6}}。
 *                       <p>⚠️ 不能漏掉它：用户特意打了一句话，写理由时却只字不提，
 *                       他会觉得"我说了它根本没听"。而那句话往往恰恰是他最在意的。
 * @param weather        当时的天气。没配高德或查不到时为 null
 * @param places         要解释的地点，按名次升序
 * @param evidenceScope  服务端确定的会话/批次/推荐行标识，仅用于计算不透明依据ID
 */
public record TravelReasonInput(
        String locationLabel,
        LocalTime now,
        int remainingMinutes,
        double maxDistanceKm,
        Integer maxTicketPrice,
        Set<TravelState> states,
        Set<TravelState> inferredStates,
        Map<TravelDimension, Double> customBiases,
        Weather weather,
        List<ExplainedPlace> places,
        String evidenceScope
) {
    /** 保留本地调用兼容；生产编排必须显式提供会话、批次和推荐记录标识。 */
    public TravelReasonInput(String locationLabel, LocalTime now, int remainingMinutes,
                             double maxDistanceKm, Integer maxTicketPrice, Set<TravelState> states,
                             Set<TravelState> inferredStates, Map<TravelDimension, Double> customBiases,
                             Weather weather, List<ExplainedPlace> places) {
        this(locationLabel, now, remainingMinutes, maxDistanceKm, maxTicketPrice, states,
                inferredStates, customBiases, weather, places, "unscoped");
    }

    /**
     * 一个待解释的地点。
     *
     * @param scorePercent 总分（0~100 的整数）
     * @param factors      五个因子。<b>这是"为什么是它"的硬事实</b>，
     *                     提示词要求模型的一切解释都要落在它们上面
     * @param ticketPrice  门票（元），0 = 免费
     * @param openFrom     地点资料中的开放时间；缺失表示未知，不证明实时营业。
     * @param topMatches   兼容旧调用的字段；无历史偏好快照时为空，不能用新反馈补算。
     * @param staticTraits 当前地点库的静态标注，不是实时客流或历史用户偏好快照。
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
            List<ScoredPlace.MatchedDimension> topMatches,
            Map<TravelDimension, Integer> staticTraits
    ) {
        public ExplainedPlace(int rank, String name, String category, String description, int scorePercent,
                              Factors factors, int ticketPrice, int suggestedMinutes, String openFrom,
                              String openTo, List<ScoredPlace.MatchedDimension> topMatches) {
            this(rank, name, category, description, scorePercent, factors, ticketPrice, suggestedMinutes,
                    openFrom, openTo, topMatches, Map.of());
        }
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
