package com.example.personality.amap;

import com.example.personality.domain.LocationInfo;

import java.util.Optional;

/**
 * 「这个坐标是哪」的抽象。
 *
 * <p>和 {@code AiReportGenerator} 是同一个套路：<b>只有一个实现的时候就把接口抽出来</b>，
 * 成本最低。这样 {@code RecommendationService} 依赖的是"能查地名"这件事，
 * 而不是"高德"这家公司——换百度、换腾讯，或者测试里塞个假的，都不用改调用方。
 *
 * <p><b>⚠️ 实现必须永不抛异常</b>，拿不到就返回 {@link Optional#empty()}。
 * 地名是锦上添花的东西，它挂了不该让推荐功能跟着挂。
 */
public interface LocationResolver {

    /**
     * 把坐标翻成地址。
     *
     * @param latitude  纬度
     * @param longitude 经度
     * @return 地址信息；查不到、超时、配额用完、没配 key 时一律返回空
     */
    Optional<LocationInfo> resolve(double latitude, double longitude);

    /** 实现方名字，出现在日志里，方便回答"这次到底是哪个实现在跑"。 */
    String providerName();
}
