package com.example.personality.dto;

import java.util.List;

/**
 * 「这次推荐是按什么处境算出来的」——回传给前端，让推断可见。
 *
 * <h2>为什么非要有这个对象</h2>
 *
 * <p>系统一旦开始<b>替用户猜</b>，就必须说清楚猜了什么。否则：
 * <ul>
 *   <li>用户看到饭点被推了一堆馆子，会以为系统坏了，而不是以为"它在按饭点推"</li>
 *   <li>想纠正也无从下手——不知道系统猜了什么，改哪个开关？</li>
 * </ul>
 *
 * <p>所以推断的正确用法是<b>「先猜，再把猜的结果摊开给用户看，并允许一键改」</b>，
 * 而不是默默按猜的结果排序。这个类就是"摊开"的那部分。
 *
 * <h2>⚠️ 现在能填的字段仍然不多</h2>
 *
 * <p>计划书里理想的处境还包括"是否在移动""附近适合做什么"——
 * 那些要更多的外部数据源。这里只列出<b>这次真的用上了</b>的，
 * 宁可少说也不装作知道。
 *
 * @param now              推算用的当前时间（服务端时间，用户所在时区）
 * @param remainingMinutes 这次按"还剩多少分钟"算的
 * @param maxDistanceKm    这次按"最远走多远"算的
 * @param maxTicketPrice   这次按"门票最多多少钱"算的。null = 不限
 * @param states           最终生效的状态（用户说的 + 系统推断的）
 * @param inferredStates   <b>其中哪些是系统自己推断出来的</b>。
 *                         前端要把这几个单独标出来——用户没说过"我饿了"，
 *                         是系统猜的，所以最容易猜错、也最该给一键改。
 * @param weather          这次用的天气。<b>可能是 null</b>——没配高德、
 *                         上游超时、或者境外查不到城市，都会是这样。
 *                         <p>注意它和 {@code states} 的区别：状态是<b>用户说的</b>
 *                         或系统按时间猜的，天气是<b>查来的事实</b>。
 *                         前者可以"猜错了，不算"，后者只能告知。
 */
public record AppliedContext(
        String now,
        int remainingMinutes,
        double maxDistanceKm,
        Integer maxTicketPrice,
        List<StateLabel> states,
        List<StateLabel> inferredStates,
        WeatherLabel weather
) {

    /**
     * 一个状态 + 它的中文名。
     *
     * <p>带上中文名是为了让前端<b>不用自己维护一份枚举翻译表</b>——
     * 后端加了新状态，前端不用改就能显示对。
     */
    public record StateLabel(String key, String label) {
    }

    /**
     * 这次用的天气。
     *
     * @param condition             原始天气描述，比如"多云""小雨"。
     *                              比类别更具体，直接显示给用户看
     * @param temperature           摄氏度
     * @param label                 拼好的展示串，比如「多云 26°」。
     *                              理由同 {@code StateLabel.label}：格式由后端定，
     *                              前端不重复实现一遍
     * @param affectsRecommendation <b>这次天气到底有没有改变排序。</b>
     *                              <p>晴天、多云、或者认不出来的天气描述都是 false——
     *                              它们不产生任何惩罚。温度计上显示"晴 30°"
     *                              没问题，但要说"我按天气调整了推荐"就不诚实了。
     *                              <p>前端据此决定要不要给这句提示：只有 true 才值得说。
     */
    public record WeatherLabel(
            String condition,
            double temperature,
            String label,
            boolean affectsRecommendation
    ) {
    }
}
