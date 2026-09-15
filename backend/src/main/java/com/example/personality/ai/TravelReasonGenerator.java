package com.example.personality.ai;

import java.util.List;

/**
 * 把「这批推荐是怎么算出来的」写成用户看得懂的几句话。
 *
 * <h2>它和前端那个「为什么是它」面板是什么关系</h2>
 *
 * <p><b>互补，不是替代。</b>面板里是一串乘法式和几个维度的对账——
 * 它回答的是"这个分数是怎么来的"，准确、可验证、但读起来是账本。
 * 这个接口产出的是人话，回答的是"<b>为什么是它，而不是另外两个</b>"。
 *
 * <p>后者恰恰是账本答不了的问题：乘法式只能说明"它自己好不好"，
 * 说明不了"它凭什么排第一"。
 *
 * <h2>⚠️ 为什么凭据是方法参数，而不是这个接口的实现类持有的字段</h2>
 *
 * <p>接入"访客自带 key"之后，同一个时刻可能有不同的人在用不同的 key。
 * 实现类是单例 Bean，**不能把 key 存在字段里**——那既服务不了多用户，
 * 也等于把别人的凭证长期留在内存里。
 *
 * <p>凭据随每次调用传进来，用完即弃，这是这个签名最要紧的一点。
 */
public interface TravelReasonGenerator {

    /**
     * 为这批推荐生成理由。
     *
     * @param credentials 这次调用用谁的 key。由 {@code AiCredentialsResolver} 解析，
     *                    可能是访客自带的，也可能是服务端配置的
     * @return 每个名次一条。<b>顺序不保证和输入一致</b>，调用方按
     *         {@link RankedReason#rank()} 对应回去
     * @throws com.example.personality.exception.AiServiceException 上游故障（502）
     * @throws com.example.personality.exception.InvalidAiCredentialsException
     *         凭据是调用方给的且被上游拒绝（400）
     */
    List<RankedReason> generateReasons(AiCredentials credentials, TravelReasonInput input);

    /**
     * 一条理由。
     *
     * @param rank   对应第几名（1 = 最推荐）
     * @param reason 那句人话
     */
    record RankedReason(int rank, String reason) {
    }
}
