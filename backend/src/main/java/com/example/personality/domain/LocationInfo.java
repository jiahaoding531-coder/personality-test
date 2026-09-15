package com.example.personality.domain;

/**
 * 一个坐标对应的"人话地址"——逆地理编码的结果。
 *
 * <h2>为什么要有这个</h2>
 *
 * <p>用户授权定位之后，前端拿到的是 {@code 30.2420, 120.1400} 这样一串数字。
 * 这串数字对用户是<b>不可验证</b>的——他没法看着它判断"系统定位对了没有"。
 * 一旦定位偏了（浏览器定位精度、用户开着代理、在公司用有线网……），
 * 整个推荐就是错的，而用户完全不知道为什么。
 *
 * <p>换成"杭州市西湖区北山街附近"，用户看一眼就知道对不对，
 * 不对就手动改。这是<b>让系统的判断可被质疑</b>，和"系统猜了什么必须摊开给人看"
 * （见 {@code AppliedContext}）是同一条产品原则。
 *
 * <h2>为什么 {@code adcode} 也在这里</h2>
 *
 * <p>高德的天气接口是<b>按城市查的，不是按坐标查的</b>——它要的是 adcode
 * （行政区划编码，杭州是 {@code 330100}）。所以"坐标 → 天气"必须走两步：
 * <pre>
 *   坐标 ──逆地理编码──► adcode ──天气接口──► 天气
 * </pre>
 * 把 adcode 一并带回来，就不用为了拿它再单独请求一次。
 *
 * <h2>为什么放在 domain 而不是 amap 包里</h2>
 *
 * <p>"这个坐标是哪"是<b>领域概念</b>，不是高德的专利。高德只是当下的实现——
 * 换百度、换腾讯，这个类一个字都不用改。amap 包里放的应该是
 * "怎么跟高德说话"（拼参数、解析它的 JSON、处理它的错误码），
 * 那种东西换服务商时必须重写，所以要和领域概念隔开。
 *
 * @param province         省。直辖市时会成为 {@link #label()} 的主体
 * @param city             市。⚠️ <b>直辖市（北京/上海/天津/重庆）这里是空串</b>——
 *                         高德对它们返回的是空数组而不是城市名，见 {@code AmapClient#text}
 * @param district         区/县
 * @param street           最近地址所在的街道，可能为空
 * @param township         乡/镇/街道办（"西湖街道"这种），街道取不到时的备选
 * @param adcode           行政区划编码，用来查天气
 * @param formattedAddress 高德拼好的完整地址（含门牌号），最权威但对用户偏长
 */
public record LocationInfo(
        String province,
        String city,
        String district,
        String street,
        String township,
        String adcode,
        String formattedAddress
) {

    /**
     * 给用户看的简短地名，形如「杭州市西湖区北山街附近」。
     *
     * <p>取 {@code street} 优先、{@code township} 兜底：前者是路名
     * （"北山街"），后者是行政街道名（"西湖街道"）。对旅行者来说路名有用得多——
     * "西湖街道"他能拿它做什么呢。
     *
     * <p><b>直辖市要用 province 顶 city</b>：北京返回的 city 是空的，
     * 不兜底的话会拼出"东城区王府井附近"，用户一看"哪个城市的东城区？"。
     *
     * <p>什么都取不到时返回空串，调用方据此当作"没有地名"处理，
     * 而不是显示一个"附近"这种没有信息量的东西。
     */
    public String label() {
        String base = isBlank(city) ? province : city;
        String detail = isBlank(street) ? township : street;

        StringBuilder sb = new StringBuilder();
        if (!isBlank(base)) {
            sb.append(base);
        }
        if (!isBlank(district)) {
            sb.append(district);
        }
        if (!isBlank(detail)) {
            sb.append(detail);
        }

        return sb.isEmpty() ? "" : sb + "附近";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
