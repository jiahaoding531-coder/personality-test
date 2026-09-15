package com.example.personality.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 把「现在几点」变成一个可注入的依赖。
 *
 * <h2>为什么不能直接调 {@code LocalTime.now()}</h2>
 *
 * <p>因为自动推断（{@code ContextInferrer}）是按时间来的：饭点推断"想吃饭"，
 * 别的时候什么都不推。如果代码里到处直接 {@code LocalTime.now()}：
 *
 * <ul>
 *   <li><b>测试会变成"看时间脸色"</b>——同一条测试中午跑绿、下午跑红。
 *       这是最难查的一类问题：不是代码错了，是跑的时候不对</li>
 *   <li><b>那个功能根本没法演示</b>——下午三点给别人看"它会推断你饿了"，
 *       演示不出来</li>
 * </ul>
 *
 * <p>注入 {@link Clock} 之后，测试里可以塞一个"永远是中午"的时钟：
 * <pre>
 *   Clock.fixed(Instant.parse("2026-09-15T04:30:00Z"), ZoneId.of("Asia/Shanghai"))
 * </pre>
 *
 * <h2>Clock 是 Java 自带的标准做法</h2>
 *
 * <p>这不是本项目发明的技巧。{@code java.time} 从设计之初就留了这个口子——
 * 凡是"取当前时间"的地方都应该走 {@code Clock}，和"取随机数"走
 * {@code Random} 的可注入实例是同一个道理。
 *
 * <p>注意时区：{@code systemDefaultZone()} 用的是服务器时区。
 * 真实产品里用户可能在别的时区，那时应该按用户所在时区构造 Clock——
 * 现在所有数据和用户都在国内，先用默认时区。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
