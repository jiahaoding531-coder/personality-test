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
 * <h2>⚠️ 现在能填的字段很少</h2>
 *
 * <p>计划书里理想的处境还包括天气、是否在移动、附近适合做什么——
 * 那些都要外部数据源。这里只列出<b>这次真的用上了</b>的，
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
 */
public record AppliedContext(
        String now,
        int remainingMinutes,
        double maxDistanceKm,
        Integer maxTicketPrice,
        List<StateLabel> states,
        List<StateLabel> inferredStates
) {

    /**
     * 一个状态 + 它的中文名。
     *
     * <p>带上中文名是为了让前端<b>不用自己维护一份枚举翻译表</b>——
     * 后端加了新状态，前端不用改就能显示对。
     */
    public record StateLabel(String key, String label) {
    }
}
