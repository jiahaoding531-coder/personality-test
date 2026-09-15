package com.example.personality.amap;

import com.example.personality.domain.LocationInfo;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 真的去调高德的逆地理编码接口。
 *
 * <p><b>只在 {@code app.amap.enabled=true} 时装配。</b>
 *
 * <h2>⚠️ 高德的坐标顺序是「经度,纬度」</h2>
 *
 * <p>参数写成 {@code location=120.1400,30.2420}——<b>经度在前</b>。
 * 这和绝大多数地图 API（以及 GeoJSON）的 {@code lat,lng} 习惯相反，
 * 是高德最容易踩的一个坑。而且<b>传反了不会报错</b>：它会把
 * "经度 30.2420" 当成一个合法经度算下去，返回一个地球另一边的地址，
 * 或者干脆返回一个看起来正常的空结果。
 *
 * <p>所以下面把顺序在代码里写死成"先经度后纬度"，并且参数名就叫
 * {@code longitudeThenLatitude}，让传参的地方无法搞错。
 */
@Component
@ConditionalOnProperty(name = "app.amap.enabled", havingValue = "true")
public class AmapLocationResolver implements LocationResolver {

    private static final String PATH = "/v3/geocode/regeo";

    private final AmapClient client;

    public AmapLocationResolver(AmapClient client) {
        this.client = client;
    }

    @Override
    public Optional<LocationInfo> resolve(double latitude, double longitude) {
        // ⚠️ 经度在前、纬度在后，且必须保留 6 位小数——
        // 高德对精度的要求是 6 位，用默认的 "30.242" 这种会损失精度
        // （约 100 米级别），对定位来说不能接受。
        String location = String.format(Locale.ROOT, "%.6f,%.6f", longitude, latitude);

        return client.get(PATH, Map.of("location", location))
                .flatMap(AmapLocationResolver::parse);
    }

    /**
     * 把高德的响应解析成 {@link LocationInfo}。
     *
     * <p><b>刻意写成 static 包级可见的纯函数</b>，不碰网络也不碰 Spring——
     * 这样解析逻辑可以直接单测（见 {@code AmapLocationResolverTest}），
     * 不需要 key、不消耗配额、不受网络影响。字段名写错这类问题
     * 用这种方式一秒就能发现，而靠手测要等到线上才发现。
     *
     * <p>结构：{@code regeocode.addressComponent.{province,city,district,
     * township,adcode,streetNumber.street}}，完整地址在
     * {@code regeocode.formatted_address}。
     */
    static Optional<LocationInfo> parse(JsonNode body) {
        JsonNode regeocode = body.path("regeocode");

        // ⚠️ isArray() 这一条是必需的，而且容易漏。
        //
        // 高德对"海面上""境外"这类查不到地址的坐标，返回的是
        // status=1 + `"regeocode": []`——注意是**空数组**，不是 null、
        // 也不是缺失。只判 missing/null 的话会漏过去，然后一路走到下面
        // 造出一个每个字段都是空串的 LocationInfo，
        // 最终在前端显示成"你在 附近"这种没有信息量的东西。
        //
        // （这个 bug 是被 AmapLocationResolverTest#emptyRegeocodeYieldsEmpty 抓出来的。）
        if (regeocode.isMissingNode() || regeocode.isNull() || regeocode.isArray()) {
            return Optional.empty();
        }

        JsonNode component = regeocode.path("addressComponent");

        return Optional.of(new LocationInfo(
                AmapClient.text(component, "province"),
                // ⚠️ 直辖市这里会是空数组，被 AmapClient.text 转成空串，
                // 由 LocationInfo.label() 用 province 兜底
                AmapClient.text(component, "city"),
                AmapClient.text(component, "district"),
                AmapClient.text(component.path("streetNumber"), "street"),
                AmapClient.text(component, "township"),
                AmapClient.text(component, "adcode"),
                AmapClient.text(regeocode, "formatted_address")
        ));
    }

    @Override
    public String providerName() {
        return "amap:regeo";
    }
}
