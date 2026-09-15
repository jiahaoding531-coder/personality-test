package com.example.personality.service;

import com.example.personality.amap.LocationResolver;
import com.example.personality.domain.LocationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 取"用户此刻所处的环境"——地名（后续还有天气）。
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

    public AmbientService(LocationResolver locationResolver) {
        this.locationResolver = locationResolver;
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
