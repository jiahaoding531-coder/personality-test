package com.example.personality.dto;

import java.util.List;
import java.util.Map;

/**
 * 「我理解成什么了」——把 AI 的解析结果**摊开**给用户。
 *
 * <h2>⚠️ 这个对象存在的唯一理由是让用户能纠正</h2>
 *
 * <p>理解了之后是**直接拿去重新推荐**的。如果用户看不到系统理解成了什么，
 * 一次误解就会表现成"这推荐怎么莫名其妙的"——
 * 用户只会觉得这东西乱来，而完全想不到是它把"想安静"听成了别的。
 *
 * <p>所以前端必须把 {@code summary} 和几个槽位显示出来，并且允许"重来"。
 * 这和项目里那条一直贯彻的原则是同一条：
 * <b>系统替用户做的判断，都要摊开给他看。</b>
 *
 * @param states           理解出的状态，带中文名（前端不用自己维护翻译表）
 * @param biases           词表覆盖不了的部分，AI 给的原始倾向
 * @param remainingMinutes 理解出的剩余时间。null = 用户没提
 * @param maxDistanceKm    理解出的距离上限。null = 用户没提
 * @param maxTicketPrice   理解出的预算上限。null = 用户没提
 * @param unrecognized     <b>听懂了但用不上的部分</b>。
 *                         <p>前端要把它们显示出来。说不出来就说"这句没用上"，
 *                         比假装听懂强——用户据此才知道系统的边界在哪
 * @param summary          AI 的一句话复述，用户扫一眼就知道理解对不对
 * @param usable           有没有解析出任何能用的条件。
 *                         <b>false 时前端该提示"换个说法"</b>，而不是拿一个空条件去重新推荐
 */
public record InterpretResponse(
        List<StateLabel> states,
        Map<String, Double> biases,
        Integer remainingMinutes,
        Double maxDistanceKm,
        Integer maxTicketPrice,
        List<String> unrecognized,
        String summary,
        boolean usable
) {

    /** 一个状态 + 它的中文名。理由同 {@code AppliedContext.StateLabel}：不让前端维护翻译表。 */
    public record StateLabel(String key, String label) {
    }
}
