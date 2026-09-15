package com.example.personality.service;

import com.example.personality.amap.WeatherProvider;
import com.example.personality.config.AmapProperties;
import com.example.personality.domain.Weather;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 天气缓存的单元测试。
 *
 * <p>这个类测的是<b>缓存这层逻辑</b>本身，所以 provider 是个假的计数器——
 * 不联网，而且能精确断言"上游到底被打了几次"。这正是不用真高德的原因：
 * 打了几次上游在真实环境里根本看不见，而它恰恰是配额会不会被用完的关键。
 *
 * <h2>⚠️ 时间必须能拨动</h2>
 *
 * <p>缓存的行为就是"过期了没有"。如果 {@code WeatherService} 里直接写
 * {@code Instant.now()}，这些用例<b>根本没法写</b>——你没法让"10 分钟过去了"
 * 在测试里发生，只能真的等 10 分钟。
 *
 * <p>下面这个 {@link MutableClock} 就是为此存在的：时间由测试推着走。
 * 这也是 {@code WeatherService} 坚持注入 {@code Clock} 而不是直接取系统时间的原因。
 */
class WeatherServiceTest {

    private static final String HANGZHOU = "330100";
    private static final String SHANGHAI = "310000";

    private static final Instant START = Instant.parse("2026-09-15T04:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);

    private final MutableClock clock = new MutableClock(START, ZoneId.of("Asia/Shanghai"));
    private final CountingProvider provider = new CountingProvider();
    private final WeatherService service = new WeatherService(provider, clock, properties(TTL));

    // ==========================================================
    // 缓存命中
    // ==========================================================

    @Test
    @DisplayName("【核心】TTL 内重复查询只打一次上游——这就是配额保护")
    void repeatedQueriesWithinTtlHitTheCache() {
        service.current(HANGZHOU);
        service.current(HANGZHOU);
        service.current(HANGZHOU);

        assertEquals(1, provider.calls.get(),
                "10 分钟内查三次天气，上游只该被调用一次");
    }

    @Test
    @DisplayName("TTL 过期后重新查上游")
    void cacheExpiresAfterTtl() {
        service.current(HANGZHOU);
        assertEquals(1, provider.calls.get());

        clock.advance(TTL.minusSeconds(1));
        service.current(HANGZHOU);
        assertEquals(1, provider.calls.get(), "还差一秒到期，不该重新查");

        clock.advance(Duration.ofSeconds(2));   // 累计超过 TTL
        service.current(HANGZHOU);
        assertEquals(2, provider.calls.get(), "过期之后应该重新查");
    }

    @Test
    @DisplayName("不同城市各自缓存，互不影响")
    void differentCitiesAreCachedSeparately() {
        service.current(HANGZHOU);
        service.current(SHANGHAI);
        service.current(HANGZHOU);
        service.current(SHANGHAI);

        assertEquals(2, provider.calls.get(), "两个城市各查一次");
    }

    // ==========================================================
    // 失败路径
    // ==========================================================

    @Test
    @DisplayName("⚠️ 查询失败不写缓存——下次会重试，不会被一次网络抖动冻住")
    void failuresAreNotCached() {
        provider.failing = true;

        assertTrue(service.current(HANGZHOU).isEmpty());
        assertTrue(service.current(HANGZHOU).isEmpty());
        assertEquals(2, provider.calls.get(),
                "失败不该被缓存：否则一次抖动会让这个城市好几分钟都没有天气");

        // 恢复之后立刻就能拿到，因为前面没把失败记进缓存
        provider.failing = false;
        assertTrue(service.current(HANGZHOU).isPresent());
        assertEquals(3, provider.calls.get());
    }

    @Test
    @DisplayName("provider 抛异常也不能冒出去，只是当作查不到")
    void providerExceptionsAreSwallowed() {
        provider.exploding = true;

        assertTrue(service.current(HANGZHOU).isEmpty(),
                "外部依赖抛异常不该让推荐接口 500");
    }

    @Test
    @DisplayName("adcode 为空时直接返回空，不打上游")
    void blankAdcodeShortCircuits() {
        assertTrue(service.current(null).isEmpty());
        assertTrue(service.current("").isEmpty());
        assertTrue(service.current("   ").isEmpty());

        assertEquals(0, provider.calls.get(),
                "没有 adcode 就不该消耗一次配额——高德会把它当成非法参数");
    }

    // ==========================================================
    // 缓存语义
    // ==========================================================

    @Test
    @DisplayName("命中缓存返回的是同一份天气，而不是重新构造一个")
    void cacheReturnsTheSameInstance() {
        Weather first = service.current(HANGZHOU).orElseThrow();
        Weather second = service.current(HANGZHOU).orElseThrow();

        assertSame(first, second, "命中的应该是缓存里那个对象");
    }

    @Test
    @DisplayName("clearCache 之后会重新查")
    void clearCacheForcesRefetch() {
        service.current(HANGZHOU);
        service.clearCache();
        service.current(HANGZHOU);

        assertEquals(2, provider.calls.get());
    }

    // ==========================================================
    // 夹具
    // ==========================================================

    private static AmapProperties properties(Duration ttl) {
        AmapProperties properties = new AmapProperties();
        properties.setWeatherCacheTtl(ttl);
        return properties;
    }

    /** 能数调用次数、能指定失败的假 provider。 */
    private static final class CountingProvider implements WeatherProvider {

        private final AtomicInteger calls = new AtomicInteger();
        private boolean failing;
        private boolean exploding;

        @Override
        public Optional<Weather> current(String adcode) {
            calls.incrementAndGet();
            if (exploding) {
                // 模拟"实现违反了接口契约"——比如某个 provider 没判空就解引用。
                // WeatherService 必须能兜住它。
                throw new IllegalStateException("provider 崩了");
            }
            return failing ? Optional.empty() : Optional.of(Weather.of("多云", 26));
        }

        @Override
        public String providerName() {
            return "counting-fake";
        }
    }

    /**
     * 可以拨动的时钟。
     *
     * <p>JDK 自带的标准做法：{@code Clock} 是抽象类，就是为了让测试能替换它。
     */
    private static final class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new MutableClock(instant, newZone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
