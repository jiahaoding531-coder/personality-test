package com.example.personality.domain;

import java.time.LocalTime;
import java.util.Set;

/**
 * 做一次推荐的「当前处境」。
 *
 * <p>计划书第十二节列了推荐分数要考虑的因素，其中这些属于"此刻的状态"
 * 而不是"用户是谁"——它们每次请求都可能不同，所以单独打包传进来，
 * <b>不持久化</b>。
 *
 * <h2>两类字段，处理方式不同</h2>
 *
 * <ul>
 *   <li><b>硬约束</b>（{@code remainingMinutes} / {@code maxDistanceKm} /
 *       {@code maxTicketPrice}）——不满足就<b>排除</b>，理由不是"分数低"而是
 *       "去了也没用"。一个需要 3 小时的地方，在你只剩 1 小时的时候，
 *       排第几名都没有意义。</li>
 *   <li><b>软状态</b>（{@code states}）——只影响<b>排序</b>，不排除任何地点。
 *       "我累了"是把费腿的地方往后排，不是把山全部删掉。</li>
 * </ul>
 *
 * @param now              当前时间（用户所在时区）。用来判断地点开没开门。
 * @param remainingMinutes 今天还剩多少可以玩的时间。
 *                         用户说"我有 3 小时"，一个需要 3.5 小时的地方就不该推荐。
 * @param latitude         用户当前位置纬度。null 表示没有定位。
 * @param longitude        用户当前位置经度。
 * @param maxDistanceKm    候选地点的最大半径。默认 10 公里。
 * @param maxTicketPrice   能接受的门票价格上限（元）。null = 不限。
 *                         <b>对应"预算不多"这个场景。</b>
 *                         ⚠️ 在加这个字段之前，{@code places.ticket_price} 虽然查出来了、
 *                         也返回给前端了，但<b>打分时一分钱都没算</b>——
 *                         推荐一个 200 块门票的地方给预算紧张的旅行者，
 *                         是"去了也没用"的典型。
 * @param states           用户此刻的状态（累了 / 饿了 / 想散步），可以同时有多个。
 *                         空集合表示没特别说明。修正幅度见 {@link TravelState}。
 * @param weather          此刻的天气。<b>可以为 null</b>——没配高德、上游超时、
 *                         或者用户在境外查不到，都会是 null。
 *                         <p>⚠️ null 时打分<b>完全不受影响</b>（天气系数恒为 1.0，
 *                         也就是乘法单位元）。这是刻意的：天气是锦上添花的数据，
 *                         拿不到就该当它不存在，而不是猜一个或报错。
 *                         <p>气候属于"此刻的处境"而不是"用户是谁"，理由同
 *                         {@link #states}——见 {@link Weather} 的类注释。
 */
public record RecommendationContext(
        LocalTime now,
        int remainingMinutes,
        Double latitude,
        Double longitude,
        double maxDistanceKm,
        Integer maxTicketPrice,
        Set<TravelState> states,
        Weather weather
) {

    public static final double DEFAULT_MAX_DISTANCE_KM = 10.0;

    /**
     * 最常用的场景：知道现在几点、还有多少时间，但没定位。测试里用得最多。
     *
     * <p>这里直接用构造器而不是走 {@link #withLocation}——后者的经纬度是
     * {@code double} 基本类型，表达不了"没有定位"（null）。
     */
    public static RecommendationContext of(LocalTime now, int remainingMinutes) {
        return new RecommendationContext(now, remainingMinutes, null, null,
                DEFAULT_MAX_DISTANCE_KM, null, Set.of(), null);
    }

    /**
     * 有定位的场景：知道在哪、现在几点、还剩多少时间。
     *
     * <p>和 {@link #of} 分开写成两个工厂方法，而不是让调用方直接
     * {@code new}——两个参数和五个参数的构造器调用点长得太像了，
     * 万一有人把经纬度传反了，编译器不会拦你（都是 double），
     * 但结果会跑到地球另一边去。
     */
    public static RecommendationContext withLocation(LocalTime now, int remainingMinutes,
                                                     double latitude, double longitude,
                                                     double maxDistanceKm) {
        return withLocation(now, remainingMinutes, latitude, longitude, maxDistanceKm, null, Set.of());
    }

    /**
     * 完整版：定位 + 预算上限 + 此刻的状态。
     *
     * <p>{@code maxTicketPrice} 传 null 表示不限；{@code states} 传 null 或空集合
     * 表示用户没说特别的状态。两者都做了兜底，调用方不用自己判空。
     */
    public static RecommendationContext withLocation(LocalTime now, int remainingMinutes,
                                                     Double latitude, Double longitude,
                                                     double maxDistanceKm,
                                                     Integer maxTicketPrice,
                                                     Set<TravelState> states) {
        return new RecommendationContext(now, remainingMinutes, latitude, longitude,
                maxDistanceKm, maxTicketPrice,
                states == null ? Set.of() : Set.copyOf(states), null);
    }

    /**
     * 补上天气，其余原样复制。
     *
     * <p>record 没有自带的 wither，而构造器有八个参数——再写一个
     * "全参数工厂方法"只会让调用点更难读（八个位置参数，谁也看不出
     * 第三个是什么）。所以给天气单独开一个：<b>它是唯一一个
     * "来自外部、可能拿不到、事后才知道"的字段。</b>
     *
     * <p>用法是 {@code context.withWeather(weather)}：
     * 前面那些参数在前端请求里就定下来了，天气要等一次网络往返。
     */
    public RecommendationContext withWeather(Weather weather) {
        return new RecommendationContext(now, remainingMinutes, latitude, longitude,
                maxDistanceKm, maxTicketPrice, states, weather);
    }

    /** 有没有定位。没有的话距离因素会失效（所有地点距离分一样）。 */
    public boolean hasLocation() {
        return latitude != null && longitude != null;
    }

    /**
     * 这个地点在不在预算内。
     *
     * <p>没设预算上限（null）时永远返回 true——"不限"和"上限为 0"是两回事，
     * 不能混。
     */
    public boolean allowsTicketPrice(int ticketPrice) {
        return maxTicketPrice == null || ticketPrice <= maxTicketPrice;
    }
}
