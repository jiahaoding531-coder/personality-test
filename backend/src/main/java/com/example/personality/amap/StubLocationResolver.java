package com.example.personality.amap;

import com.example.personality.domain.LocationInfo;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 没有配置高德时的占位实现：永远查不到地名。
 *
 * <p><b>它存在的意义不是"以后可能有用"，而是让整条链路在没有外部依赖时也是通的。</b>
 * 别人 clone 这个仓库、直接 {@code mvnw spring-boot:run}，不会因为缺一个 key
 * 就起不来——推荐照样能出，只是结果里没有地名。
 *
 * <p>和 {@link AmapLocationResolver} 用<b>同一个配置项的正反条件</b>互斥装配，
 * 保证容器里任何时候<b>恰好有一个</b> {@code LocationResolver}：
 * 不会出现"找不到 Bean"，也不会出现"两个候选不知道该注入哪个"。
 *
 * <p>⚠️ 不要图省事改成 {@code @ConditionalOnMissingBean}。那个注解的生效
 * 依赖 Bean 的注册顺序，在这个项目里顺序是不确定的——正反条件是确定的。
 */
@Component
@ConditionalOnProperty(name = "app.amap.enabled", havingValue = "false", matchIfMissing = true)
public class StubLocationResolver implements LocationResolver {

    @Override
    public Optional<LocationInfo> resolve(double latitude, double longitude) {
        return Optional.empty();
    }

    @Override
    public String providerName() {
        return "stub(no-amap)";
    }
}
