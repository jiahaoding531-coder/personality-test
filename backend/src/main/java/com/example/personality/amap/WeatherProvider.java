package com.example.personality.amap;

import com.example.personality.domain.Weather;

import java.util.Optional;

/**
 * 「此刻什么天气」的抽象。
 *
 * <p>和 {@link LocationResolver} 一样：只在有一个实现的时候抽出来，成本最低；
 * 换数据源（和风、OpenWeather）时调用方一行不用改。
 *
 * <p><b>⚠️ 实现必须永不抛异常</b>，拿不到就返回 {@link Optional#empty()}。
 * 天气是锦上添花的数据，它挂了不该让推荐跟着挂。
 */
public interface WeatherProvider {

    /**
     * 查某个城市的实时天气。
     *
     * @param adcode 行政区划编码，比如杭州是 {@code 330100}。
     *               <p>⚠️ <b>不是经纬度。</b>高德的天气接口按城市查，
     *               所以要先做一次逆地理编码拿到 adcode——两步走，
     *               见 {@link com.example.personality.domain.LocationInfo} 的类注释
     * @return 天气；查不到、超时、配额用完、没配 key 时一律返回空
     */
    Optional<Weather> current(String adcode);

    /** 实现方名字，出现在日志里。 */
    String providerName();
}
