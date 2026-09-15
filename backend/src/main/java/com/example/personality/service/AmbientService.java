package com.example.personality.service;

import com.example.personality.amap.LocationResolver;
import com.example.personality.domain.AmbientContext;
import com.example.personality.domain.LocationInfo;
import com.example.personality.domain.Weather;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 取"用户此刻所处的环境"——地名和天气。
 *
 * <p>这两样都需要联网（逆地理编码、天气接口），也都有可能是拿不到的。
 * 拿齐之后打包成 {@link AmbientContext} 交给推荐服务。
 *
 * <h2>⚠️ 这个类存在的唯一理由是「事务边界」</h2>
 *
 * <p>它干的事看着很简单，本来完全可以塞进 {@link RecommendationService}——
 * 而那正是<b>不能</b>做的事。
 *
 * <p>{@code RecommendationService.recommend} 上有 {@code @Transactional}，
 * 意味着整个方法期间都占着一个数据库连接（本项目 {@code open-in-view: false}，
 * 连接由事务持有）。而调高德是<b>一次几百毫秒到 5 秒的网络等待</b>。
 * 连接池上限是 10，只要 10 个人同时点"给我推荐"，连接就全被
 * "正在等高德回话"的请求占住，其他人的请求（包括登录、看历史）
 * 会在 30 秒后超时失败。
 *
 * <p>这个教训项目里已经吃过一次——见 {@code AiReportService}：
 * 大模型调用被特意挪到了事务外面。
 *
 * <p>所以划一条线：<b>所有需要网络等待的取数，都在事务外完成，
 * 把结果当参数传进去。</b>这个类就是那条线的事务外一侧。
 * 控制器先调它拿环境，再调 {@code recommend} 做纯本地的计算和落库。
 *
 * <h2>失败一律降级</h2>
 *
 * <p>这个类的任何方法都<b>不会抛异常</b>。拿不到地名就没有地名，
 * 推荐照样出——地名是锦上添花，不该让主功能跟着挂。
 */
@Service
public class AmbientService {

    private static final Logger log = LoggerFactory.getLogger(AmbientService.class);

    private final LocationResolver locationResolver;
    private final WeatherService weatherService;

    public AmbientService(LocationResolver locationResolver, WeatherService weatherService) {
        this.locationResolver = locationResolver;
        this.weatherService = weatherService;
    }

    /**
     * 取齐这次推荐需要的所有环境信息。
     *
     * <p>两步是<b>串行</b>的，不能并发：天气是按城市查的，而"哪个城市"
     * 要先由逆地理编码从坐标算出来。所以这里必然是
     * <pre>
     *   坐标 → 城市(adcode) → 天气
     * </pre>
     * 两次网络往返。这是这条链路上唯一的延迟来源，也是为什么要给
     * {@code app.amap.timeout} 设一个短值（默认 5 秒）。
     *
     * <p>好消息是第二次查询（天气）有按城市的缓存，所以常态下只有
     * 逆地理编码这一次真的走网络——但注意<b>天气的缓存不会让这两步
     * 变成并发</b>，只是让第二步通常在内存里就返回了。
     *
     * <p>任何一步失败都不会中断：拿不到城市就没有天气，
     * 返回的对象里相应字段为 null，调用方按正常降级处理。
     */
    public AmbientContext resolve(Double latitude, Double longitude) {
        LocationInfo location = resolveLocation(latitude, longitude).orElse(null);
        if (location == null) {
            // 没定位就没有城市，也就没有天气。**不去猜一个城市**——
            // 按 IP 猜或者按上次的位置猜，都可能给出一个完全无关的天气，
            // 而用户会以为系统知道他换了地方。
            return AmbientContext.EMPTY;
        }

        return AmbientContext.of(location, resolveWeather(location));
    }

    /**
     * 拿这个城市此刻的天气。
     *
     * <p>{@link #resolveLocation} 和这里都做了 try/catch 兜底，
     * 看起来重复（provider 的接口契约已经说不抛异常了），但这是刻意的：
     * 这一层是"软依赖"原则的最后一道闸，<b>它一旦漏了，整个推荐接口就 500</b>。
     * 四行 catch 换这个保证是划算的。
     */
    private Weather resolveWeather(LocationInfo location) {
        if (location.adcode() == null || location.adcode().isBlank()) {
            // 逆地理编码成功但没带 adcode（境外坐标就是这样）。
            // 没有 adcode 就查不了天气，这不算异常。
            log.debug("没有 adcode，跳过天气查询 label={}", location.label());
            return null;
        }

        try {
            return weatherService.current(location.adcode()).orElse(null);
        } catch (RuntimeException e) {
            log.warn("天气查询失败 adcode={}", location.adcode(), e);
            return null;
        }
    }

    /**
     * 把坐标翻成人话地名。
     *
     * @return 地名；没有定位、查不到、或者上游出任何问题时返回空
     */
    public Optional<LocationInfo> resolveLocation(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            // 没有定位就没有地名可言。这是正常路径（用户拒绝授权定位时会这样），
            // 不是错误——所以这里连日志都不记。
            return Optional.empty();
        }

        try {
            Optional<LocationInfo> resolved = locationResolver.resolve(latitude, longitude);
            resolved.ifPresent(info -> log.debug("逆地理编码成功 provider={} label={}",
                    locationResolver.providerName(), info.label()));
            return resolved;
        } catch (RuntimeException e) {
            // 接口契约说实现永不抛异常，这里仍然兜一层。
            // 理由是"违反契约的代价不对等"：provider 少写一个判空，
            // 后果是整个推荐接口 500，而这里加一个 catch 只要四行。
            log.warn("逆地理编码失败 provider={}", locationResolver.providerName(), e);
            return Optional.empty();
        }
    }
}
