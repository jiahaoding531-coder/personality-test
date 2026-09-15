package com.example.personality.domain;

/**
 * 用户此刻所处的「环境」——需要外部数据源才能知道的那部分。
 *
 * <h2>为什么要有这个类，而不是直接传两个参数</h2>
 *
 * <p>两个理由：
 *
 * <p><b>1）它们是一起取回来的。</b>天气要先知道城市才能查，而城市来自
 * 逆地理编码——两步是串起来的，天然的同一件事。分开传会掩盖这个依赖。
 *
 * <p><b>2）它们都要在事务外取。</b>这是更要紧的一点：{@code RecommendationService}
 * 的方法是事务性的，里面不能有网络调用。把"需要联网才知道的东西"打包成
 * 一个对象，就等于给"事务外该做完哪些事"画了一条明确的线——
 * 看到这个类型，就知道它必须是在进事务之前准备好的。
 *
 * <h2>⚠️ 两个字段都可以为 null，而且那是常态</h2>
 *
 * <p>没配高德、用户拒绝定位、上游超时、境外查不到……任何一种情况都会让
 * 其中一项变成 null。业务代码必须<b>把 null 当成正常输入</b>来处理，
 * 而不是当成错误：
 *
 * <ul>
 *   <li>{@code location == null} → 响应里没有地名，其余不变</li>
 *   <li>{@code weather == null} → {@code weatherFactor} 恒为 1.0，打分不变</li>
 * </ul>
 *
 * <p>这正是"外部依赖必须是软的"这条设计原则的具体形状：<b>拿不到环境，
 * 推荐照样是个完整的推荐，只是少了一些修饰。</b>
 *
 * @param location 坐标对应的人话地名，可能为 null
 * @param weather  此刻的天气，可能为 null
 */
public record AmbientContext(LocationInfo location, Weather weather) {

    /** 什么都没有的处境。没定位、或者整个高德都没配时用它。 */
    public static final AmbientContext EMPTY = new AmbientContext(null, null);

    public static AmbientContext of(LocationInfo location, Weather weather) {
        return new AmbientContext(location, weather);
    }
}
