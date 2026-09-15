package com.example.personality.entity;

import com.example.personality.domain.TravelState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 一批推荐**当时用掉的处境**，对应 recommendation_batches 表。
 *
 * <h2>为什么单独一张表</h2>
 *
 * <p>处境是<b>批次级</b>的事实，不是地点级的。同一批的三个地点共享同一份处境
 * （同样几点、同样还剩 240 分钟、同样下着雨），塞进 {@code recommendations}
 * 就是把同一份数据抄三遍——抄三遍就有"三行对不上"的可能，而那种数据错误
 * 事后根本查不出哪个是对的。
 *
 * <pre>
 *   recommendation_batches  1 ──&lt; 3  recommendations
 *   （这批是怎么算的）              （算出来是哪几个地方）
 * </pre>
 *
 * <h2>它是为 AI 推荐理由而生的</h2>
 *
 * <p>推荐和理由是<b>两次请求</b>：推荐要秒回（纯本地计算），AI 要几秒，
 * 不能绑在一起。第二次请求只能从数据库重建"当初是怎么算的"——
 * 没有这张表，AI 拿到的就只有一个孤零零的 63%，而 63% 什么都说明不了。
 *
 * <p>⚠️ 这里**不存用户坐标**。处境里真正影响推荐的是"还剩多久""什么状态"
 * "什么天气"这些，而"离多远"已经被折算进每条推荐的
 * {@code distance_factor} 里了——存一份原始坐标反而会多出一个
 * "坐标和因子对不上"的可能。
 *
 * <h2>这张表也是 append-only 的</h2>
 *
 * <p>和 {@link Recommendation} 一样：一批一行，永不修改。
 */
@Entity
@Table(name = "recommendation_batches")
public class RecommendationBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "batch_no", nullable = false)
    private int batchNo;

    /** 用户坐标对应的人话地名，比如"杭州市西湖区北山街附近"。没配高德时为 null。 */
    @Column(name = "location_label", length = 128)
    private String locationLabel;

    /** 算这批推荐时是几点（用户所在时区）。 */
    @Column(name = "context_time", nullable = false)
    private LocalTime contextTime;

    /** 当时还剩多少可玩时间（分钟）。 */
    @Column(name = "remaining_minutes", nullable = false)
    private int remainingMinutes;

    @Column(name = "max_distance_km", nullable = false, precision = 5, scale = 2)
    private BigDecimal maxDistanceKm;

    /** 预算上限（元）。null = 不限。 */
    @Column(name = "max_ticket_price")
    private Integer maxTicketPrice;

    /**
     * 当时生效的状态，逗号分隔的<b>枚举名</b>（如 {@code "TIRED,HUNGRY"}）。
     *
     * <p>⚠️ 存枚举名而不是中文。中文展示名是 {@link TravelState} 上的产品文案，
     * 会改；存进数据库就会和代码里的定义脱节，改了文案老数据还是旧的。
     * 重建提示词时用 {@code TravelState.valueOf(...).label()} 现取中文。
     */
    @Column(name = "states", nullable = false, length = 128)
    private String states = "";

    /**
     * 其中哪些是<b>系统自己推断</b>的。
     *
     * <p>⚠️ 单独记住这个，是为了让 AI 不把猜测说成事实。
     * "你说了你累了"和"系统猜你累了"是两回事——前者是用户提供的，
     * 后者是我们替他做的判断，转述出去时不能混。
     */
    @Column(name = "inferred_states", nullable = false, length = 128)
    private String inferredStates = "";

    @Column(name = "weather_condition", length = 64)
    private String weatherCondition;

    @Column(name = "weather_temperature", precision = 4, scale = 1)
    private BigDecimal weatherTemperature;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RecommendationBatch() {
    }

    /**
     * 记下一批推荐的处境。
     *
     * @param states         最终生效的状态（用户说的 + 系统推断的）
     * @param inferredStates 其中系统推断的部分。<b>必须是 {@code states} 的子集</b>——
     *                       "推断出来的"东西如果压根没生效，记下来只会误导
     */
    public static RecommendationBatch of(Long sessionId, int batchNo,
                                         String locationLabel,
                                         LocalTime contextTime, int remainingMinutes,
                                         BigDecimal maxDistanceKm, Integer maxTicketPrice,
                                         Set<TravelState> states, Set<TravelState> inferredStates,
                                         String weatherCondition, Double weatherTemperature) {
        RecommendationBatch batch = new RecommendationBatch();
        batch.sessionId = sessionId;
        batch.batchNo = batchNo;
        batch.locationLabel = locationLabel;
        batch.contextTime = contextTime;
        batch.remainingMinutes = remainingMinutes;
        batch.maxDistanceKm = maxDistanceKm;
        batch.maxTicketPrice = maxTicketPrice;
        batch.states = joinStates(states);
        batch.inferredStates = joinStates(inferredStates);
        batch.weatherCondition = weatherCondition;
        batch.weatherTemperature = weatherTemperature == null
                ? null
                : BigDecimal.valueOf(weatherTemperature);
        return batch;
    }

    private static String joinStates(Set<TravelState> states) {
        if (states == null || states.isEmpty()) {
            return "";
        }
        return states.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    /**
     * 把逗号分隔的枚举名读回状态集合。
     *
     * <p><b>认不出来的名字直接跳过，不抛异常。</b>这个值是从数据库读回来的，
     * 可能来自一个已经删掉了某个枚举值的旧版本——那种情况下，
     * "少一个历史状态"远好过"整个理由功能挂掉"。
     */
    public Set<TravelState> stateSet() {
        return parseStates(states);
    }

    public Set<TravelState> inferredStateSet() {
        return parseStates(inferredStates);
    }

    private static Set<TravelState> parseStates(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<TravelState> result = new LinkedHashSet<>();
        for (String name : raw.split(",")) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Arrays.stream(TravelState.values())
                    .filter(state -> state.name().equals(trimmed))
                    .findFirst()
                    .ifPresent(result::add);
        }
        return result;
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

    public String getLocationLabel() {
        return locationLabel;
    }

    public LocalTime getContextTime() {
        return contextTime;
    }

    public int getRemainingMinutes() {
        return remainingMinutes;
    }

    public BigDecimal getMaxDistanceKm() {
        return maxDistanceKm;
    }

    public Integer getMaxTicketPrice() {
        return maxTicketPrice;
    }

    public String getWeatherCondition() {
        return weatherCondition;
    }

    public BigDecimal getWeatherTemperature() {
        return weatherTemperature;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
