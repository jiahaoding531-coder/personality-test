package com.example.personality.dto;

import com.example.personality.domain.RecommendationContext;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 请求推荐时的「当前处境」。
 *
 * <h2>为什么定位是必填的</h2>
 *
 * <p>定位和剩余时长在引擎里都是"可以没有"的（没有定位就不算距离因素，
 * 见 {@code RecommendationEngine}）。但接口上把定位定成必填，是产品判断：
 *
 * <p><b>旅行助手不知道你在哪，给出的"推荐"就没有意义。</b>
 * 没有定位时引擎仍然会返回一个排序结果，但那是按"兴趣匹配 + 质量"排的，
 * 和"你附近此刻最值得去哪"是两回事——与其返回一个看起来正常、
 * 实际上没考虑距离的结果，不如直接要求调用方给定位。
 *
 * <p>剩余时长则相反，它是可以合理默认的（默认 4 小时），
 * 而且用户经常说不清"还有多久"，所以留成可选。
 *
 * @param latitude         用户当前位置纬度。<b>必填。</b>
 * @param longitude        用户当前位置经度。<b>必填。</b>
 * @param remainingMinutes 今天还剩多少可以玩的时间（分钟）。不传默认 240 分钟（4 小时）。
 *                         一个建议停留 3 小时的地方，在"只剩 2 小时"时会被直接排除。
 * @param maxDistanceKm    候选地点的最大半径（公里）。不传默认 10 公里。
 * @param excludeSeen      是否排除这个会话里已经推荐过、且用户没点过 👍 的地点。
 *                         "换一批"按钮要传 {@code true}，否则重新请求会拿到一模一样的结果
 *                         （引擎没有记忆，同样的输入必然算出同样的输出）。
 *                         <b>不传默认 false</b>——保持既有行为不变，也让"刷新页面"可预测。
 */
public record RecommendationRequest(

        @NotNull(message = "latitude 不能为空")
        @DecimalMin(value = "-90.0", message = "latitude 必须在 -90 ~ 90 之间")
        @DecimalMax(value = "90.0", message = "latitude 必须在 -90 ~ 90 之间")
        Double latitude,

        @NotNull(message = "longitude 不能为空")
        @DecimalMin(value = "-180.0", message = "longitude 必须在 -180 ~ 180 之间")
        @DecimalMax(value = "180.0", message = "longitude 必须在 -180 ~ 180 之间")
        Double longitude,

        @Min(value = 15, message = "remainingMinutes 至少为 15 分钟")
        @Max(value = 1440, message = "remainingMinutes 最多为 1440 分钟（24 小时）")
        Integer remainingMinutes,

        @DecimalMin(value = "0.5", message = "maxDistanceKm 至少为 0.5 公里")
        @DecimalMax(value = "50.0", message = "maxDistanceKm 最多为 50 公里")
        Double maxDistanceKm,

        Boolean excludeSeen
) {

    /** 不传剩余时长时的默认值：4 小时。够逛两个景点，是比较典型的半日行程。 */
    public static final int DEFAULT_REMAINING_MINUTES = 240;

    /**
     * 剩余时长，没传就用默认值。
     *
     * <p>把"默认值"这件事收在 DTO 里，而不是让 Service 到处写
     * {@code x == null ? 240 : x}——默认值是接口契约的一部分，
     * 应该和字段定义放在一起。
     */
    public int remainingMinutesOrDefault() {
        return remainingMinutes == null ? DEFAULT_REMAINING_MINUTES : remainingMinutes;
    }

    public double maxDistanceKmOrDefault() {
        return maxDistanceKm == null ? RecommendationContext.DEFAULT_MAX_DISTANCE_KM : maxDistanceKm;
    }

    public boolean excludeSeenOrDefault() {
        return Boolean.TRUE.equals(excludeSeen);
    }
}
