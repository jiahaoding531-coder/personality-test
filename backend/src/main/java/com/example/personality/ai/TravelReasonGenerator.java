package com.example.personality.ai;

import java.util.List;

/**
 * 把「这批推荐是怎么算出来的」写成用户看得懂的几句话。
 *
 * <p>和 {@code AiReportGenerator} 是同一个套路：接口 + 真/桩两个实现 +
 * 正反条件互斥装配。默认关掉，别人 clone 仓库不配 key 也能跑。
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
 * <h2>⚠️ 实现必须只依据 {@link TravelReasonInput} 里的事实</h2>
 *
 * <p>任何编造——"据说要排队两小时""周末有市集"——只要输入里没有，
 * 就是在骗用户。用户按着这句话做了决定、到了地方发现不对，
 * 损失的不只是这一次出行，而是对整个助手的信任。
 */
public interface TravelReasonGenerator {

    /**
     * 为这批推荐生成理由。
     *
     * @return 每个名次一条。<b>顺序不保证和输入一致</b>，调用方按
     *         {@link RankedReason#rank()} 对应回去
     * @throws com.example.personality.exception.NotImplementedException
     *         未配置 AI 时（装配的是桩实现）
     * @throws com.example.personality.exception.AiServiceException
     *         调用上游失败、或返回的内容解析不出来
     */
    List<RankedReason> generateReasons(TravelReasonInput input);

    /** 实现方名字，会随响应返回，便于排查"这次到底是哪个模型写的"。 */
    String providerName();

    /**
     * 一条理由。
     *
     * @param rank   对应第几名（1 = 最推荐）
     * @param reason 那句人话
     */
    record RankedReason(int rank, String reason) {
    }
}
