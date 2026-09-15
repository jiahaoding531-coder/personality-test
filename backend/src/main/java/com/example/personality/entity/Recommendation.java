package com.example.personality.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 一条推荐记录，对应 recommendations 表。
 *
 * <p>一次推荐请求产生 Top 3，也就是 3 行。
 *
 * <p>为什么要存下来而不是算完直接返回？
 * <ol>
 *   <li><b>用户反馈要挂到某条推荐上</b>——没有记录就没地方挂"我不喜欢这个"</li>
 *   <li><b>接受率是个需要历史才能算的指标</b>。计划书第十九节把
 *       "随使用次数增加，推荐接受率是否提升"列为核心验证问题之一，
 *       那必须有历史数据</li>
 * </ol>
 *
 * <h2>⚠️ 这张表是 append-only 的</h2>
 *
 * <p>重新推荐<b>不是</b>"先删旧的再插新的"，而是新开一批（{@code batchNo + 1}），
 * 旧的那批连同用户给它的反馈一起留着。
 *
 * <p>理由是数据安全：{@code recommendation_feedback} 外键挂在推荐行上，
 * 删推荐会级联把用户点过的 👍/👎 一起删掉。而反馈恰恰是这个项目里
 * 最该攒下来的数据——每重新推荐一次就清零，攒了等于没攒。
 *
 * <p>所以这个类里<b>没有</b> {@code setBatchNo}，构造完就是只读的；
 * Repository 里也<b>不该</b>有按 sessionId 删除的方法。
 */
@Entity
@Table(name = "recommendations")
public class Recommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /**
     * 这个会话的第几批推荐，从 1 开始。
     *
     * <p>用户改主意（"我累了"）、天气变了、时间变了，都会触发重新推荐。
     * 每重新推荐一次就 +1，历史批次不删。
     */
    @Column(name = "batch_no", nullable = false)
    private int batchNo = 1;

    /** 名次，1 = 最推荐。列名用 rank_no 避开 SQL 的窗口函数关键字 RANK。 */
    @Column(name = "rank_no", nullable = false)
    private int rankNo;

    @Column(name = "place_id", nullable = false)
    private Long placeId;

    /**
     * 推荐得分 0.000 ~ 1.000。
     *
     * <p>用 {@link BigDecimal} 对应数据库的 {@code numeric(4,3)}——
     * 分数要展示、要比较、要写进测试断言，浮点误差会带来持续的困扰。
     */
    @Column(name = "score", nullable = false, precision = 4, scale = 3)
    private BigDecimal score;

    // ==========================================================
    // 五个打分因子（V11 新增）
    // ==========================================================
    // 它们和 score 的关系是 score = 五者相乘。
    //
    // 【为什么存下来】
    // 生成 AI 推荐理由时，需要**重建当初是怎么算的**——而理由是另一次请求，
    // 那时内存里早就什么都没有了。只有一个总分说明不了任何事：
    // 同样是 63%，可能是"兴趣很合但有点远"，也可能是"兴趣一般但就在楼下"。
    //
    // 【⚠️ 为什么可空】
    // V11 之前的推荐记录没有这些值（不是"算出来是 0"，是**从来没算过**）。
    // 留 NULL 表示"未知"，语义准确；硬填默认值等于编造历史。

    @Column(name = "interest_factor", precision = 4, scale = 3)
    private BigDecimal interestFactor;

    @Column(name = "distance_factor", precision = 4, scale = 3)
    private BigDecimal distanceFactor;

    @Column(name = "quality_factor", precision = 4, scale = 3)
    private BigDecimal qualityFactor;

    @Column(name = "state_factor", precision = 4, scale = 3)
    private BigDecimal stateFactor;

    @Column(name = "weather_factor", precision = 4, scale = 3)
    private BigDecimal weatherFactor;

    /** AI 或规则生成的推荐理由。可空。 */
    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Recommendation() {
    }

    /**
     * 五个打分因子，打包传递。
     *
     * <p>为什么不直接给 {@link #of} 加五个 {@code BigDecimal} 参数：
     * 它们类型完全一样、含义却完全不同，<b>顺序传错了编译器一个字都不会说</b>，
     * 而结果是一份看起来正常、实际全错的因子记录——这种数据错误事后
     * 几乎不可能被发现。打包成一个有名字的对象，调用点就必须写明
     * "哪个是哪个"。
     *
     * <p>（同样的理由见 {@code RecommendationContext} 里关于经纬度传反的那段注释。）
     */
    public record Factors(BigDecimal interest, BigDecimal distance, BigDecimal quality,
                          BigDecimal state, BigDecimal weather) {
    }

    /**
     * 造一条推荐记录。
     *
     * @param batchNo 第几批（从 1 开始）。同一次推荐请求产出的 Top 3 用同一个值
     * @param score   引擎算出来的分。<b>必须 ≤ 1</b>，数据库上有约束卡着
     * @param factors 五个因子。<b>必须和 score 满足"相乘等于 score"</b>——
     *                这条不变量数据库表达不了，靠引擎的单元测试守着
     */
    public static Recommendation of(Long sessionId, int batchNo, int rankNo,
                                    Long placeId, BigDecimal score, Factors factors) {
        Recommendation r = new Recommendation();
        r.sessionId = sessionId;
        r.batchNo = batchNo;
        r.rankNo = rankNo;
        r.placeId = placeId;
        r.score = score;
        r.interestFactor = factors.interest();
        r.distanceFactor = factors.distance();
        r.qualityFactor = factors.quality();
        r.stateFactor = factors.state();
        r.weatherFactor = factors.weather();
        return r;
    }

    /** 生成理由之后回填。抽成方法而不是暴露 setter，让"什么时候能改"变得明确。 */
    public void attachReason(String reason) {
        this.reason = reason;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public int getBatchNo() {
        return batchNo;
    }

    public int getRankNo() {
        return rankNo;
    }

    public Long getPlaceId() {
        return placeId;
    }

    public BigDecimal getScore() {
        return score;
    }

    public String getReason() {
        return reason;
    }

    /** 五个因子。V11 之前的历史记录全是 null。 */
    public Factors getFactors() {
        return new Factors(interestFactor, distanceFactor, qualityFactor,
                stateFactor, weatherFactor);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
