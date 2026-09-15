package com.example.personality.domain;

/**
 * 天气的<b>类别</b>——把各家 API 五花八门的天气描述串归一成有限的几种。
 *
 * <h2>为什么要归一，不直接用原始字符串</h2>
 *
 * <p>高德返回的 {@code weather} 字段有三十多个取值：晴、多云、阴、小雨、中雨、
 * 大到暴雨、雷阵雨伴有冰雹、雨夹雪、浮尘、扬沙、强沙尘暴……
 * 如果让算法去认这些字符串，等于把"什么算恶劣天气"这条产品判断
 * 散落在一堆 {@code contains("雨")} 里，而且换一家数据源就得重写。
 *
 * <p>归一成枚举之后，映射集中在 {@link #fromCondition(String)} 一处，
 * 而"这个类别对户外地点有多不友好"写在枚举自己身上。
 *
 * <h2>⚠️ 影响写在枚举上，不让调用方传系数</h2>
 *
 * <p>这条和 {@link TravelState} 是同一个原则：<b>"下雨意味着什么"是产品知识，
 * 不是调用方该知道的事。</b>如果让 {@code RecommendationEngine} 自己去判断
 * "雨该扣 0.5 还是 0.3"，那这个数字就会散落在算法里，改一次要翻好几个文件。
 *
 * <h2>为什么不分雨的大小</h2>
 *
 * <p>高德能区分"小雨"和"暴雨"，这里刻意都归成 {@link #RAIN}。<b>这是一个
 * 有意的简化</b>：靠中文字符串匹配来判断雨量等级很脆（"小到中雨""大到暴雨"
 * 这些组合值一个比一个长），而收益有限——对"要不要去户外"这个决策来说，
 * "在下雨"比"下多大"重要得多。真要做到雨量分级，应该等接了
 * 带数值的结构化天气数据（降水量 mm）再说，而不是去猜字符串。
 */
public enum WeatherKind {

    /** 晴。户外友好。 */
    CLEAR("晴", 0.0),

    /** 多云。户外友好。 */
    CLOUDY("多云", 0.0),

    /** 阴天。户外友好——阴天拍照反而好看，不惩罚。 */
    OVERCAST("阴", 0.0),

    /**
     * 下雨（含阵雨、雷阵雨、暴雨）。
     *
     * <p>惩罚系数 0.5：<b>完全户外的地点在雨天得分砍半。</b>
     * 这个幅度是刻意的——要大到能让"西湖·苏堤"从第一掉下去，
     * 又不能大到把户外地点彻底抹掉（那就成了硬过滤，而天气按设计
     * 只该影响排序，见 {@code RecommendationContext} 里软硬状态的区分）。
     */
    RAIN("雨", 0.5),

    /** 下雪（含雨夹雪）。同雨，但对"费腿"的影响更大——路面滑。 */
    SNOW("雪", 0.5),

    /**
     * 能见度差：雾、霾、浮尘、扬沙、沙尘暴。
     *
     * <p>惩罚比雨雪轻：不影响"能不能去"，主要影响"值不值得去"
     * （看不到远景、拍照不好看）。
     */
    HAZE("雾霾", 0.3),

    /**
     * 认不出来的天气描述。
     *
     * <p><b>不惩罚</b>。理由和别处的降级逻辑一致：不确定的时候不要瞎猜。
     * 猜错的代价是一个好地方被无端降权，而用户完全不知道原因——
     * 与其这样，不如什么都不做。
     */
    UNKNOWN("未知", 0.0);

    private final String label;

    private final double outdoorPenalty;

    WeatherKind(String label, double outdoorPenalty) {
        this.label = label;
        this.outdoorPenalty = outdoorPenalty;
    }

    /** 中文展示名。 */
    public String label() {
        return label;
    }

    /**
     * 这个天气对<b>完全户外</b>（indoor=0）地点的惩罚幅度，0~1。
     *
     * <p>0 表示"不影响"；0.5 表示"完全户外的地点得分砍半"。
     * 实际惩罚会再按地点的 {@code indoor} 打折——全程室内的地点
     * （indoor=100）不受任何影响。
     *
     * <p>⚠️ 这个值必须落在 0~1。它会被用来算
     * {@code weatherFactor = 1 - penalty × 户外程度}，
     * 而 score 上有 {@code CHECK (score BETWEEN 0 AND 1)} 卡着，
     * 系数大于 1 会让接口在存库时直接 500。
     */
    public double outdoorPenalty() {
        return outdoorPenalty;
    }

    /**
     * 把高德（或任何数据源）的天气描述串映射成类别。
     *
     * <h2>⚠️ 判断顺序是有讲究的</h2>
     *
     * <p>先雨雪、再雾霾、最后才是晴/云/阴。因为高德有"雨夹雪""雷阵雨伴有冰雹"
     * 这类<b>复合描述</b>，先判"晴"的话不会出问题（它们不含"晴"字），
     * 但先判"云"就会——"多云转小雨"里既有云又有雨，而它该按雨算。
     *
     * <p><b>所以从"最影响决策的"往"最不影响决策的"排。</b>
     * 这也解释了为什么雨雪排在雾霾前面：同时出现时，按更严重的算。
     *
     * <p>认不出来返回 {@link #UNKNOWN}，不抛异常——天气数据来自外部，
     * 不该因为多了一个没见过的描述就让推荐挂掉。
     */
    public static WeatherKind fromCondition(String condition) {
        if (condition == null || condition.isBlank()) {
            return UNKNOWN;
        }
        if (condition.contains("雨")) {
            return RAIN;
        }
        if (condition.contains("雪")) {
            return SNOW;
        }
        if (condition.contains("雾") || condition.contains("霾")
                || condition.contains("沙") || condition.contains("尘")) {
            return HAZE;
        }
        if (condition.contains("晴")) {
            return CLEAR;
        }
        if (condition.contains("云")) {
            return CLOUDY;
        }
        if (condition.contains("阴")) {
            return OVERCAST;
        }
        return UNKNOWN;
    }
}
