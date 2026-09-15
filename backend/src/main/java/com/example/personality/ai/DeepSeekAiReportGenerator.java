package com.example.personality.ai;

import com.example.personality.dto.SessionResultResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 真实的 AI 反馈生成：调 DeepSeek 把人格画像写成一段话。
 *
 * <p><b>只在 {@code app.ai.enabled=true} 时装配。</b>
 * 未启用时容器里是 {@link StubAiReportGenerator}（返回 501）。
 * 两个实现用同一个配置项的正反条件互斥，保证任何时候都<b>恰好有一个</b>
 * {@code AiReportGenerator} Bean 存在——不会出现"找不到 Bean"或
 * "两个候选不知道该注入哪个"。
 *
 * <h2>这个类现在只剩两件事</h2>
 *
 * <p>组织提示词（{@link AiPromptBuilder}），和让 {@link DeepSeekChatClient}
 * 把话带到。HTTP、超时、错误处理、响应解析全都在那个客户端里——
 * 因为旅行推荐理由要用同一套，而复制一份就等于把那些踩过的坑埋两遍。
 *
 * <p>DeepSeek 的接口和 OpenAI 完全兼容（路径 {@code /chat/completions}、
 * 同样的 {@code messages} 结构、同样的 {@code choices[0].message.content}），
 * 所以换成通义、智谱、Kimi 多数只要改 {@code app.ai.base-url} 和 {@code model}，
 * 这两个类一行都不用动。这也是为什么配置项要抽成 {@code AiProperties}。
 */
@Component
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true")
public class DeepSeekAiReportGenerator implements AiReportGenerator {

    private final DeepSeekChatClient chatClient;
    private final AiPromptBuilder promptBuilder;

    public DeepSeekAiReportGenerator(DeepSeekChatClient chatClient,
                                     AiPromptBuilder promptBuilder) {
        this.chatClient = chatClient;
        this.promptBuilder = promptBuilder;
    }

    @Override
    public String generateReport(SessionResultResponse result) {
        // 人格解读是一整段散文，不需要 JSON 模式
        return chatClient.complete(
                promptBuilder.systemPrompt(),
                promptBuilder.userPrompt(result));
    }

    @Override
    public String providerName() {
        return chatClient.providerName();
    }
}
