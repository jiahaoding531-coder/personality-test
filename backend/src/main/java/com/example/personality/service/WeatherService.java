package com.example.personality.service;

import com.example.personality.amap.WeatherProvider;
import com.example.personality.config.AmapProperties;
import com.example.personality.domain.Weather;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 天气查询 + 按城市缓存。
 *
 * <h2>缓存是必须的，不是优化</h2>
 *
 * <p>推荐是"用户点一下就走一次"的高频操作，而个人 Key 的天气接口有每日
 * 调用上限。不缓存的话，一个下午的反复调试就能把当天额度用光——
 * 而额度用完的表现是"天气突然一直没有了"，很难联想到是这个原因。
 *
 * <p>按 <b>adcode（城市）</b>缓存，所以同一个城市的所有用户共享一份。
 * 缓存时长见 {@code app.amap.weather-cache-ttl}，默认 10 分钟——
 * 高德的实时天气本身也是十几分钟才更新一次，缓 10 分钟和现查没有区别。
 *
 * <h2>⚠️ 走注入的 Clock，不用 Instant.now()</h2>
 *
 * <p>理由和 {@code ClockConfig} 里说的一样，但在这里格外要紧：
 * 缓存的行为就是"过期了没有"，<b>直接取系统时间的话，这个类根本没法测</b>——
 * 你没法让"10 分钟过去了"这件事在测试里发生，只能真的等 10 分钟。
 * 注入 Clock 之后，测试里塞一个能拨动的时钟就全解决了
 * （见 {@code WeatherServiceTest}）。
 *
 * <h2>缓存的是「成功」，不缓存「失败」</h2>
 *
 * <p>查失败（超时、配额用完、key 错）时不写缓存，下次请求会重新尝试。
 * 取舍是这样的：缓存失败能少打几次上游，但代价是<b>一次网络抖动会让这个城市
 * 好几分钟都没有天气</b>——而抖动的恢复通常比 10 分钟快得多。
 *
 * <p>万一上游真的持续不可用，每次请求会白等一个超时（默认 5 秒），
 * 那是 {@code app.amap.timeout} 该调小的时候，而不是靠缓存失败来兜。
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    private final WeatherProvider provider;
    private final Clock clock;
    private final Duration cacheTtl;

    /**
     * adcode → 缓存的天气。
     *
     * <p>用 {@link ConcurrentHashMap} 而不是 {@code HashMap}：这是单例 Bean，
     * 会被多个请求线程同时读写。普通 HashMap 在并发扩容时可能死循环或丢数据，
     * 而且这种问题只在压测时才现形。
     */
    private final Map<String, CachedWeather> cache = new ConcurrentHashMap<>();

    public WeatherService(WeatherProvider provider, Clock clock, AmapProperties properties) {
        this.provider = provider;
        this.clock = clock;
        this.cacheTtl = properties.getWeatherCacheTtl();
    }

    /**
     * 查某个城市此刻的天气，带缓存。
     *
     * @param adcode 行政区划编码。为空时直接返回空（不打上游）
     * @return 天气；命中缓存直接返回，否则查上游并写入缓存。
     *         <b>任何失败都返回空，不抛异常</b>
     */
    public Optional<Weather> current(String adcode) {
        if (adcode == null || adcode.isBlank()) {
            return Optional.empty();
        }

        Instant now = clock.instant();

        CachedWeather cached = cache.get(adcode);
        if (cached != null && !cached.isExpired(now, cacheTtl)) {
            log.debug("天气命中缓存 adcode={} weather={}", adcode, cached.weather().label());
            return Optional.of(cached.weather());
        }

        Optional<Weather> fetched = fetch(adcode);
        fetched.ifPresent(weather -> cache.put(adcode, new CachedWeather(weather, now)));
        return fetched;
    }

    /** 清空缓存。给测试用——否则用例之间会互相污染。 */
    public void clearCache() {
        cache.clear();
    }

    private Optional<Weather> fetch(String adcode) {
        try {
            Optional<Weather> weather = provider.current(adcode);
            weather.ifPresentOrElse(
                    w -> log.info("天气查询成功 adcode={} provider={} weather={}",
                            adcode, provider.providerName(), w.label()),
                    () -> log.debug("天气查询无结果 adcode={} provider={}",
                            adcode, provider.providerName()));
            return weather;
        } catch (RuntimeException e) {
            // 接口契约说实现永不抛异常，这里仍然兜一层。
            // 理由和 AmbientService 那边一样：provider 少写一个判空，
            // 后果是整个推荐接口 500，而这里加个 catch 只要四行。
            log.warn("天气查询失败 adcode={} provider={}", adcode, provider.providerName(), e);
            return Optional.empty();
        }
    }

    /**
     * 一条缓存记录：天气本身 + 什么时候取回来的。
     *
     * <p>存"取回来的时刻"而不是"什么时候过期"，是因为前者是事实、
     * 后者是策略。TTL 改了以后，旧记录会自然而然地按新规则重新判断，
     * 不用去迁移已经写进缓存里的过期时间。
     */
    private record CachedWeather(Weather weather, Instant fetchedAt) {

        boolean isExpired(Instant now, Duration ttl) {
            return !now.isBefore(fetchedAt.plus(ttl));
        }
    }
}
