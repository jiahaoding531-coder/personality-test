package com.example.personality.dto;

import java.time.Instant;
import java.util.List;

/**
 * 一次推荐的结果：Top 3（或更少）。
 *
 * <h2>为什么是"一批"而不是"一份"</h2>
 *
 * <p>用户改主意（"我累了"）、换个定位、时间变了，都会重新请求推荐。
 * 每次请求都会在 {@code recommendations} 表里<b>新开一批</b>
 * （{@code batchNo} 从 1 开始递增），旧的那批连同用户给它的反馈一起留着。
 *
 * <p>所以响应里必须带上 {@code batchNo}——前端拿到之后，
 * 后续给某一条点 👍/👎 时要能说清"我评价的是哪一批里的第几名"。
 *
 * <p>⚠️ 这也是为什么没有"重新推荐时先删掉旧的"这种实现：
 * 删推荐会级联删掉用户反馈，而反馈是整个项目里最该攒下来的数据。
 * 完整理由见 V9 迁移脚本。
 *
 * @param sessionId      会话 ID
 * @param batchNo        这是该会话的第几批推荐，从 1 开始
 * @param generatedAt    这批推荐的生成时间
 * @param places         Top N，按分数降序。<b>可能是空列表</b>——比如定位附近
 *                       10 公里内没有营业中的地点时，这是正常结果，不是错误
 * @param appliedContext <b>这次是按什么处境算的</b>，含"哪些是系统自己推断的"。
 *                       前端必须把它显示出来——系统一旦开始替用户猜，
 *                       就得说清楚猜了什么，否则用户莫名其妙，也没法纠正
 * @param locationLabel  这次用的坐标对应的人话地名，形如「杭州市西湖区北山街附近」。
 *                       <b>可能是 null</b>——没定位、或没配置高德时就是这样。
 *                       <p>它的作用是让定位<b>可被验证</b>：用户授权定位后拿到的
 *                       只是一串经纬度，他没法看着它判断准不准；换成地名，
 *                       偏了一眼就能看出来。这和 {@code appliedContext}
 *                       是同一个原则——<b>系统替你做的判断，都要摊开给你看</b>
 */
public record RecommendationResponse(
        Long sessionId,
        int batchNo,
        Instant generatedAt,
        List<RecommendedPlace> places,
        AppliedContext appliedContext,
        String locationLabel
) {
}
