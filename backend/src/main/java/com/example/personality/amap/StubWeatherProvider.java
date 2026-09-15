package com.example.personality.amap;

import com.example.personality.domain.Weather;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 没有配置高德时的占位实现：永远查不到天气。
 *
 * <p>它让"没有天气"成为一条<b>被完整走过的路径</b>，而不是一段没人执行过的
 * 分支代码——所有测试、所有本地开发，走的都是这条。
 *
 * <p>于是 {@code weatherFactor} 恒为 1.0、打分与接天气之前完全一致，
 * 既有的测试一个都不用改。这是"外部依赖必须是软的"这条原则的落点。
 *
 * <p>和 {@link AmapWeatherProvider} 用同一个配置项的正反条件互斥装配，
 * 保证容器里<b>恰好有一个</b> {@code WeatherProvider}。
 */
@Component
@ConditionalOnProperty(name = "app.amap.enabled", havingValue = "false", matchIfMissing = true)
public class StubWeatherProvider implements WeatherProvider {

    @Override
    public Optional<Weather> current(String adcode) {
        return Optional.empty();
    }

    @Override
    public String providerName() {
        return "stub(no-amap)";
    }
}
