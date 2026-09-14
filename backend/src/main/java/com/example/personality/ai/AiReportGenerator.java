package com.example.personality.ai;

import com.example.personality.dto.SessionResultResponse;

/**
 * 生成个性化反馈文本的抽象。
 *
 * <p><b>这个接口是 V0.1 就建好、但留到 V0.2 才真正实现的"预埋件"。</b>
 *
 * <p>为什么要现在就抽出接口，而不是等到 V0.2 再写？
 * 因为"依赖倒置"这件事必须在<b>只有一个实现</b>的时候做，成本最低。
 * 等 V0.2 再抽，就得同时改动 AiReportService、测试、配置多处，
 * 而那时你已经忘了 V0.1 的设计意图。
 *
 * <p>V0.2 的接入步骤（不需要动 AiReportService 一行代码）：
 * <ol>
 *   <li>新建 {@code DeepSeekAiReportGenerator implements AiReportGenerator}，
 *       内部用 RestClient 调 {@code https://api.deepseek.com/v1/chat/completions}</li>
 *   <li>给它加 {@code @Component}；给桩实现加 {@code @ConditionalOnProperty}
 *       或直接删掉</li>
 *   <li>把 API Key 读自环境变量 {@code DEEPSEEK_API_KEY}（不要写死在代码里，
 *       更不要提交到 Git——这是开源项目最常见的翻车方式）</li>
 * </ol>
 *
 * <p>提示词里应该包含的信息（V0.2 直接用）：5 个维度的中文名、分值、档位、
 * 以及 {@code itemCount}。把 {@code itemCount} 带上是为了让模型知道
 * "这个分数是几道题算出来的"，否则它可能编出"你答了 40 道题"这种与事实不符的话。
 */
public interface AiReportGenerator {

    /**
     * 根据人格画像生成一段个性化反馈。
     *
     * @param result 已经算好的画像结果
     * @return 反馈正文（纯文本或 Markdown）
     */
    String generateReport(SessionResultResponse result);

    /** 实现方的名字，会显示在 API 响应里，便于排查"这次到底是哪个实现在跑"。 */
    String providerName();
}
