package com.example.personality.domain;

/**
 * 此刻的天气。
 *
 * <h2>它属于「此刻的处境」，不属于「用户是谁」</h2>
 *
 * <p>这一点决定了它放在哪里：和 {@link TravelState}（"我累了"）一样，
 * 它每次请求都可能不同，<b>不持久化</b>，也不该写进用户画像。
 * 今天下雨不代表这个用户喜欢室内——明天出太阳，同样的画像应该
 * 推出完全不同的结果。
 *
 * <p>所以它挂在 {@code RecommendationContext} 上，和"还剩多少时间"
 * "最多走多远"是同一层的东西。
 *
 * <h2>天气怎么影响打分</h2>
 *
 * <p>只做一件事：<b>把户外的地方往后压。</b>
 * <pre>
 *   weatherFactor = 1 - 惩罚幅度 × 户外程度
 *   户外程度 = 1 - indoor/100
 * </pre>
 *
 * <ul>
 *   <li>全程室内（indoor=100）→ 户外程度 0 → 系数恒为 <b>1.0</b>，完全不受影响</li>
 *   <li>完全户外（indoor=0）→ 户外程度 1 → 系数 = 1 - 惩罚幅度</li>
 * </ul>
 *
 * <p><b>⚠️ 好天气不做正向奖励。</b>晴天不会让系数大于 1——那样 score 就可能
 * 超过 1，而数据库上有 {@code CHECK (score BETWEEN 0 AND 1)}，
 * 存库时会直接 500。这也和 {@code qualityFactor} 的取舍一致：
 * 修正因子只往下扣，不往上加。
 *
 * <p>语义上也说得通：<b>晴天不需要被鼓励，下雨天才需要被避开。</b>
 *
 * @param condition   原始天气描述，比如"多云""小雨"。直接来自数据源，
 *                    展示给用户看的就是它（比枚举的中文名更具体）
 * @param temperature 摄氏度。用来判断高温/严寒
 * @param kind        归一化后的类别，决定户外惩罚的基准幅度
 */
public record Weather(
        String condition,
        double temperature,
        WeatherKind kind
) {

    /**
     * 高温线。达到或超过就额外惩罚户外地点。
     *
     * <p>35°C 是中国气象上"高温天气"的标准线，不是我随手定的数字。
     */
    public static final double HOT_THRESHOLD = 35.0;

    /** 严寒线。0°C 以下，户外待久了不舒服。 */
    public static final double COLD_THRESHOLD = 0.0;

    /** 高温对户外地点的惩罚幅度。比下雨轻——热还能忍，淋湿不能。 */
    public static final double HEAT_PENALTY = 0.3;

    /** 严寒的惩罚幅度。比高温再轻一点：穿厚点就行了。 */
    public static final double COLD_PENALTY = 0.25;

    /**
     * 从原始描述构造，类别自动推断。
     *
     * <p>让调用方（{@code AmapWeatherProvider}）不用自己去查映射表——
     * 它只管把 JSON 里的字段读出来，归类是领域层的事。
     */
    public static Weather of(String condition, double temperature) {
        return new Weather(condition, temperature, WeatherKind.fromCondition(condition));
    }

    /**
     * 这个天气对<b>完全户外</b>地点的惩罚幅度，0~1。
     *
     * <p>取「天气类别」和「温度」里更严重的那个，而不是相加——
     * 相加会突破 1（0.5 的雨 + 0.3 的高温 = 0.8，再来个严寒就爆了），
     * 而系数一旦大于 1 就违反 score ≤ 1 的不变量。
     *
     * <p>语义上取最大值也更对：下雨<b>而且</b>高温，并不会比"只是下雨"
     * 难受一倍，它只是同样让人不想待在户外。
     *
     * <p>⚠️ 返回值必须 ≤ 1，理由同上。
     */
    public double outdoorPenalty() {
        double penalty = kind.outdoorPenalty();

        if (temperature >= HOT_THRESHOLD) {
            penalty = Math.max(penalty, HEAT_PENALTY);
        } else if (temperature <= COLD_THRESHOLD) {
            penalty = Math.max(penalty, COLD_PENALTY);
        }

        return penalty;
    }

    /** 给用户看的一句话，比如「多云 26°」。 */
    public String label() {
        return condition + " " + Math.round(temperature) + "°";
    }

    /**
     * 值不值得跟用户提一句"我按天气调整了排序"。
     *
     * <p>只有真的影响了打分才提示。晴天也弹一句"已考虑天气"的话，
     * 这个提示很快就变成噪音，用户会连真正重要的提示一起忽略——
     * 和 {@code AppliedContext.inferredStates} 只列"真的生效了的推断"
     * 是同一条原则：<b>说出来的每一条都得是有信息量的。</b>
     */
    public boolean affectsRecommendation() {
        return outdoorPenalty() > 0;
    }
}
