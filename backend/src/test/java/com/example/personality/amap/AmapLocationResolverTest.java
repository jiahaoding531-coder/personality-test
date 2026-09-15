package com.example.personality.amap;

import com.example.personality.domain.LocationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逆地理编码响应解析的单元测试。
 *
 * <p><b>不联网、不需要 Key、不消耗配额。</b>因为 {@code parse} 是纯函数——
 * 给它一段 JSON，它给你一个 {@link LocationInfo}。这正是当初把它写成
 * static 方法而不是塞进 {@code resolve()} 里的原因。
 *
 * <p>它拦的是哪类问题：<b>字段路径写错了</b>。高德的响应嵌套很深
 * （{@code regeocode.addressComponent.streetNumber.street}），
 * 少写一层不会报错，只会静默地拿到空串——表现是"地名显示不全"，
 * 而这种问题靠手测要碰运气才发现，靠真实接口测又慢又费配额。
 *
 * <p>下面用的 JSON 是<b>真实调接口抓下来的形状</b>（含西湖的实测响应），
 * 不是照着文档编的——文档和实际返回不一致是常有的事，
 * 只有真实响应才能保证测试有意义。
 */
class AmapLocationResolverTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 真实响应（西湖，实测于 2026-09-15），截去了与解析无关的字段。 */
    private static final String WEST_LAKE = """
            {
              "status": "1",
              "info": "OK",
              "infocode": "10000",
              "regeocode": {
                "addressComponent": {
                  "city": "杭州市",
                  "province": "浙江省",
                  "district": "西湖区",
                  "township": "西湖街道",
                  "adcode": "330106",
                  "streetNumber": {
                    "street": "北山街",
                    "number": "101号",
                    "distance": "167.567"
                  }
                },
                "formatted_address": "浙江省杭州市西湖区北山街101号"
              }
            }
            """;

    private static Optional<LocationInfo> parse(String json) {
        return AmapLocationResolver.parse(JSON.readTree(json));
    }

    // ==========================================================
    // 正常路径
    // ==========================================================

    @Test
    @DisplayName("普通城市：省市区街道都解析出来")
    void parsesOrdinaryCity() {
        LocationInfo info = parse(WEST_LAKE).orElseThrow();

        assertEquals("浙江省", info.province());
        assertEquals("杭州市", info.city());
        assertEquals("西湖区", info.district());
        assertEquals("北山街", info.street());
        assertEquals("330106", info.adcode());
        assertEquals("浙江省杭州市西湖区北山街101号", info.formattedAddress());
    }

    @Test
    @DisplayName("地名取街道优先：拼成「杭州市西湖区北山街附近」")
    void labelPrefersStreet() {
        assertEquals("杭州市西湖区北山街附近", parse(WEST_LAKE).orElseThrow().label());
    }

    @Test
    @DisplayName("街道取不到时退回 township，而不是拼出个没有细节的地名")
    void labelFallsBackToTownship() {
        String json = WEST_LAKE.replace("\"street\": \"北山街\"", "\"street\": []");

        assertEquals("杭州市西湖区西湖街道附近", parse(json).orElseThrow().label());
    }

    // ==========================================================
    // ⚠️ 高德的两个著名怪癖
    // ==========================================================

    @Test
    @DisplayName("【怪癖】直辖市：city 返回的是空数组，必须用 province 兜底")
    void municipalityFallsBackToProvince() {
        // 北京/上海/天津/重庆的 city 字段是 []（数组！），不是字符串。
        // 不兜底的话会拼出"东城区王府井附近"——用户一看"哪个城市的东城区？"
        String beijing = """
                {
                  "status": "1", "info": "OK", "infocode": "10000",
                  "regeocode": {
                    "addressComponent": {
                      "province": "北京市",
                      "city": [],
                      "district": "东城区",
                      "township": "东华门街道",
                      "adcode": "110101",
                      "streetNumber": { "street": "王府井大街" }
                    },
                    "formatted_address": "北京市东城区王府井大街"
                  }
                }
                """;

        LocationInfo info = parse(beijing).orElseThrow();

        // ⚠️ 这里必须是空串而不是 "[]" 或异常——AmapClient.text 会把数组节点吃掉
        assertEquals("", info.city(), "直辖市的 city 是空数组，解析后应当是空串");
        assertEquals("北京市东城区王府井大街附近", info.label());
    }

    @Test
    @DisplayName("【怪癖】海上/境外：status=1 但 regeocode 是空数组，算「没结果」不算错误")
    void emptyRegeocodeYieldsEmpty() {
        // 高德对查不到地址的坐标返回 status=1 + regeocode:[]。
        // 这不是失败，是"这个坐标没有对应地址"——必须返回空 Optional，
        // 而不是造一个各字段都是空串的 LocationInfo（那会显示成"附近"）。
        String atSea = """
                {"status": "1", "info": "OK", "infocode": "10000", "regeocode": []}
                """;

        assertTrue(parse(atSea).isEmpty(), "regeocode 为空数组时应当没有结果");
    }

    @Test
    @DisplayName("字段整个缺失也不能崩，只是解析出来是空的")
    void missingFieldsDoNotThrow() {
        String bare = """
                {"status": "1", "regeocode": {"addressComponent": {}}}
                """;

        // 不抛异常，而且 label() 返回空串（调用方据此当作"没有地名"）
        Optional<LocationInfo> info = parse(bare);
        assertTrue(info.isPresent());
        assertEquals("", info.orElseThrow().label(),
                "什么都取不到时应当是空串，而不是显示一个没有信息量的「附近」");
    }
}
