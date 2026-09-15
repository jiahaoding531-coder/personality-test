package com.example.personality.ai;

/**
 * 把用户打的一句大白话翻译成结构化输入。
 *
 * <p>和其他生成器一样：凭据随每次调用传进来，**不存字段**——
 * 单例 Bean 服务所有访客，内存里不留任何人的 key。
 */
public interface TextIntentGenerator {

    /**
     * @param credentials 这次用谁的 key
     * @param text        用户的原话
     * @return 解析结果。**解析不出任何东西时返回 {@link TextIntentResult#empty()} 而不是抛异常**——
     *         "这句我听不懂"是一个正常的结果，不是故障，前端会据此提示用户换个说法
     * @throws com.example.personality.exception.AiServiceException 上游故障（502）
     * @throws com.example.personality.exception.InvalidAiCredentialsException
     *         凭据是用户给的且被上游拒绝（400）
     */
    TextIntentResult interpret(AiCredentials credentials, String text);
}
