package com.example.personality.ai;

import com.example.personality.dto.SessionResultResponse;

/**
 * 生成个性化反馈文本的抽象。
 *
 * <p><b>这个接口是 V0.1 就建好、但留到 V0.2 才真正实现的"预埋件"。</b>
 * 为什么要现在就抽出接口，而不是等到实现时再写？
 * 因为"依赖倒置"这件事必须在<b>只有一个实现</b>的时候做，成本最低。
 *
 * <h2>⚠️ 凭据是方法参数，不是实现类持有的字段</h2>
 *
 * <p>接入"访客自带 key"之后，同一个时刻可能不同的人在用不同的 key。
 * 实现类是单例 Bean，**不能把 key 存在字段里**——既服务不了多用户，
 * 也等于把别人的凭证长期留在内存里。
 *
 * <p>（这也顺带说明了一件事：<b>接口的形状会随着需求变</b>。
 * 当初那句"提前抽出接口，将来改造成本最低"是对的，但"抽出来就不用再动"
 * 从来不是承诺——这次就动了，而且动得不大，正因为边界早就划好了。）
 */
public interface AiReportGenerator {

    /**
     * 根据人格画像生成一段个性化反馈。
     *
     * @param credentials 这次调用用谁的 key
     * @param result      已经算好的画像结果
     * @return 反馈正文（纯文本或 Markdown）
     */
    String generateReport(AiCredentials credentials, SessionResultResponse result);
}
