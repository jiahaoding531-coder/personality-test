package com.example.personality.entity;

import com.example.personality.domain.DimensionScore;
import com.example.personality.domain.TravelDimension;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/**
 * 旅行偏好画像，对应 travel_profiles 表。
 *
 * <p>8 个维度，每个 0~100。和 {@code PersonalityProfile} 是平行的两张表——
 * 理由见 V8 迁移脚本里的说明（维度集合固定且数量少，用固定列比行式结构好用）。
 *
 * <p><b>为什么用 {@link BigDecimal} 而不是 int：</b>
 * 计分器输出的是两位小数。旅行量表目前每维度只有 1 道题，分数恰好都是整数
 * （0/25/50/75/100），但那是"当前题目数量"的巧合——题目一加就会出现 12.5 这种值。
 * 用 int 存就得先四舍五入，把精度**静默**抹掉，而且数据库的 CHECK 是 0~100，
 * 抹平后照样通过，不会报任何错。人格画像那边用的也是 NUMERIC(5,2)，两边保持一致。
 */
@Entity
@Table(name = "travel_profiles")
public class TravelProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, unique = true)
    private Long sessionId;

    @Column(name = "nature", nullable = false, precision = 5, scale = 2)
    private BigDecimal nature;
    @Column(name = "culture", nullable = false, precision = 5, scale = 2)
    private BigDecimal culture;
    @Column(name = "food", nullable = false, precision = 5, scale = 2)
    private BigDecimal food;
    @Column(name = "photography", nullable = false, precision = 5, scale = 2)
    private BigDecimal photography;
    @Column(name = "hidden_gems", nullable = false, precision = 5, scale = 2)
    private BigDecimal hiddenGems;
    @Column(name = "crowd_tolerance", nullable = false, precision = 5, scale = 2)
    private BigDecimal crowdTolerance;
    @Column(name = "walking", nullable = false, precision = 5, scale = 2)
    private BigDecimal walking;
    @Column(name = "planning", nullable = false, precision = 5, scale = 2)
    private BigDecimal planning;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TravelProfile() {
    }

    /**
     * 从「维度 → 分数」的映射构建实体。
     *
     * <p>参数用 Map 而不是 8 个 BigDecimal，是为了让调用方（计分服务）
     * 能以循环的方式填值，不用写 8 行展开的代码。
     */
    public static TravelProfile from(Long sessionId,
                                     Map<TravelDimension, DimensionScore<TravelDimension>> scores) {
        TravelProfile p = new TravelProfile();
        p.sessionId = sessionId;
        p.nature = normalizedOf(scores, TravelDimension.NATURE);
        p.culture = normalizedOf(scores, TravelDimension.CULTURE);
        p.food = normalizedOf(scores, TravelDimension.FOOD);
        p.photography = normalizedOf(scores, TravelDimension.PHOTOGRAPHY);
        p.hiddenGems = normalizedOf(scores, TravelDimension.HIDDEN_GEMS);
        p.crowdTolerance = normalizedOf(scores, TravelDimension.CROWD_TOLERANCE);
        p.walking = normalizedOf(scores, TravelDimension.WALKING);
        p.planning = normalizedOf(scores, TravelDimension.PLANNING);
        return p;
    }

    /**
     * 从计分结果里取某个维度的归一化分。
     *
     * <p>取不到就抛异常，正常情况下不会发生（{@code ScoringService} 会先拦），
     * 这里只是最后一道防御——和 {@code PersonalityProfile.normalizedOf} 一个套路。
     */
    private static BigDecimal normalizedOf(Map<TravelDimension, DimensionScore<TravelDimension>> scores,
                                           TravelDimension dimension) {
        DimensionScore<TravelDimension> score = scores.get(dimension);
        if (score == null) {
            throw new IllegalStateException("旅行偏好缺少维度：" + dimension.label());
        }
        return score.normalized();
    }

    /**
     * 按维度取分（精确值，两位小数）。
     *
     * <p>展示用这个——前端要显示 "NATURE 75.00" 而不是被抹平过的整数。
     */
    public BigDecimal scoreOf(TravelDimension dimension) {
        return switch (dimension) {
            case NATURE -> nature;
            case CULTURE -> culture;
            case FOOD -> food;
            case PHOTOGRAPHY -> photography;
            case HIDDEN_GEMS -> hiddenGems;
            case CROWD_TOLERANCE -> crowdTolerance;
            case WALKING -> walking;
            case PLANNING -> planning;
        };
    }

    /**
     * 转成推荐引擎要的「维度 → 整数权重」映射。
     *
     * <p><b>这里是精度从"两位小数"降到"整数"的唯一一处</b>，
     * 而且是刻意的：推荐引擎的权重（以及地点属性）本来就是 0~100 的整数，
     * 兴趣匹配是个加权平均，多出的小数位对排序结果没有实际影响。
     *
     * <p>两步走：先 {@code setScale(0, HALF_UP)} 四舍五入，
     * 再 {@code intValueExact()} 转换——后者在值超出 int 范围时会抛异常，
     * 而不是像 {@code intValue()} 那样**静默截断**。这里不可能超（有 CHECK 卡 0~100），
     * 但用 exact 版本能让"不可能"变成"编译器/运行时帮你确认过"。
     */
    public Map<TravelDimension, Integer> toPreferenceMap() {
        Map<TravelDimension, Integer> map = new EnumMap<>(TravelDimension.class);
        for (TravelDimension dimension : TravelDimension.values()) {
            map.put(dimension, scoreOf(dimension).setScale(0, RoundingMode.HALF_UP).intValueExact());
        }
        return map;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
