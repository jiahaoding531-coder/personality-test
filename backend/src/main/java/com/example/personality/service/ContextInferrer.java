package com.example.personality.service;

import com.example.personality.domain.TravelState;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 「当前处境」的自动推断——不让用户填，系统自己猜。
 *
 * <h2>为什么要这一层</h2>
 *
 * <p>用户不该为了拿到一个推荐先填五个输入框。能用规则推出来的就别问——
 * 这也正是"Agent"和"表单"的区别：前者自己补信息，后者让用户干活。
 *
 * <h2>⚠️ 现在只能推时间，别的一概推不了</h2>
 *
 * <p>理想情况下这里应该结合定位、天气、步数、移动状态……
 * 但<b>那些数据现在一个都没有</b>：天气没接、真实 POI 没有、
 * 手机的移动状态要 App 才拿得到（浏览器里的 {@code watchPosition} 耗电且要持续授权）。
 *
 * <p>所以这里<b>只做一件有把握的事：按时间推断</b>。宁可少推、
 * 也绝不装作能推——猜错了比不猜更糟，因为用户会莫名其妙。
 *
 * <h2>推断必须可见、可撤销</h2>
 *
 * <p>这是这个类存在的<b>前提条件</b>：推断出来的状态会随响应一起回传
 * （{@code appliedContext.inferredStates}），前端要把"我按什么推的"显示出来，
 * 并且允许一键改。默默猜一个"你饿了"然后按这个排序，用户只会觉得系统坏了。
 *
 * <h2>纯逻辑类</h2>
 *
 * <p>和 {@code ScoringService} / {@code RecommendationEngine} 一样：
 * 不碰数据库、不碰 Spring（{@code @Service} 只是注册标签），
 * 可以直接 {@code new} 出来喂固定的时间做单测——这一点对"按时间推断"尤其重要，
 * 否则测试就会依赖"跑测试的时候是不是饭点"。
 */
@Service
public class ContextInferrer {

    /** 午饭时段的起止。取 11:30 ~ 13:00，比"12 点前后"宽一点，符合真实的午饭习惯。 */
    private static final LocalTime LUNCH_FROM = LocalTime.of(11, 30);
    private static final LocalTime LUNCH_TO = LocalTime.of(13, 0);

    /** 晚饭时段。比午饭长：晚饭的跨度本来就更随意。 */
    private static final LocalTime DINNER_FROM = LocalTime.of(17, 30);
    private static final LocalTime DINNER_TO = LocalTime.of(19, 30);

    /**
     * 按当前时间推断用户此刻可能的状态。
     *
     * <p>目前只有一条规则：<b>饭点 → 可能想吃饭</b>。
     *
     * <p>为什么先做这一条：
     * <ul>
     *   <li>它是<b>零外部依赖</b>的——只需要一个 {@link LocalTime}，
     *       而时间在服务端本来就有</li>
     *   <li>它的<b>把握度足够高</b>：饭点想吃饭是个很安全的先验，
     *       猜错的代价小（用户看到"我按饭点推的"可以一键改）</li>
     * </ul>
     *
     * <p><b>刻意没做的推断</b>（以及为什么）：
     * <ul>
     *   <li>"天黑了 → 推荐室内"——<b>没有室内/户外的数据</b>。
     *       地点的类别是 NATURE / MUSEUM / FOOD 这些，不等于"室内外"。
     *       硬推只能是瞎猜</li>
     *   <li>"用户在景区附近 → 他累了"——没有任何依据，
     *       而且刚到的游客和逛了一下午的游客完全是两种状态</li>
     * </ul>
     *
     * @param now 用户所在时区的当前时间
     * @return 推断出的状态，可能为空集（非饭点就是空）
     */
    public Set<TravelState> inferStates(LocalTime now) {
        Set<TravelState> states = new LinkedHashSet<>();
        if (isMealTime(now)) {
            states.add(TravelState.HUNGRY);
        }
        return states;
    }

    /**
     * 是不是饭点（午饭或晚饭时段）。
     *
     * <p>用「左闭右开」：{@code 11:30} 算饭点，{@code 13:00} 不算——
     * 时段边界只属于一边，否则 13:00 和 13:00:01 都要判一次，容易写错。
     */
    boolean isMealTime(LocalTime now) {
        return inRange(now, LUNCH_FROM, LUNCH_TO) || inRange(now, DINNER_FROM, DINNER_TO);
    }

    private static boolean inRange(LocalTime now, LocalTime from, LocalTime to) {
        // isBefore(from) 为 false 且 isBefore(to) 为 true ⇒ [from, to)
        return !now.isBefore(from) && now.isBefore(to);
    }
}
