package com.example.personality.dto;

import java.util.List;

/**
 * 一批推荐的人话理由。
 *
 * <h2>为什么是独立接口，而不是塞进推荐响应里</h2>
 *
 * <p>推荐是<b>本地算法算的，毫秒级</b>；AI 理由要调大模型，<b>几秒</b>。
 * 绑在一起的话，"拿推荐"这个产品最核心的交互就得等着 AI——
 * 而且 AI 一慢或一挂，推荐本身也跟着变慢、变不可用。
 *
 * <p>分开之后前端可以先秒出列表，理由到了再填进去。
 *
 * @param sessionId 会话 ID
 * @param batchNo   这份理由是给<b>哪一批</b>的。
 *                  <p>⚠️ 前端必须用它和当前展示的批次比对——连点「换一批」会并发
 *                  好几个请求，先发的可能后回来，不比对就会把旧批次的理由
 *                  贴到新列表上
 * @param provider  哪个模型写的，比如 "deepseek:deepseek-chat"。
 *                  排查"这次内容怎么怪怪的"时能立刻知道是谁产出的
 * @param cached    true 表示直接返回了之前生成好的，<b>这次没有调用大模型</b>。
 *                  用户误点两次不该白花两次 token，也不该看到两段不一样的话
 * @param reasons   每条理由。可能<b>比地点数少</b>——模型偶尔会漏掉某个名次，
 *                  那种情况下宁可少给一条，也不该让整批作废
 */
public record TravelReasonResponse(
        Long sessionId,
        int batchNo,
        String provider,
        boolean cached,
        List<PlaceReason> reasons
) {

    /**
     * 一条理由，挂在某条推荐上。
     *
     * @param recommendationId 对应 {@code recommendations.id}。
     *                         前端按它把理由贴到对应的卡片上——
     *                         <b>用 id 而不是名次</b>，因为名次是"这一批里的位置"，
     *                         而 id 才能唯一标识"用户正在看的那张卡片"
     * @param rank             第几名，从 1 开始
     * @param reason           那句人话
     */
    public record PlaceReason(Long recommendationId, int rank, String reason) {
    }
}
