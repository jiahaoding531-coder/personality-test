package com.example.personality.ai;

import com.example.personality.dto.SessionResultResponse;
import org.springframework.stereotype.Component;

/**
 * 把人格画像写成一段话。
 *
 * <h2>⚠️ 这里不再有 {@code @ConditionalOnProperty}</h2>
 *
 * <p>以前这个类和 {@code StubAiReportGenerator} 用 {@code app.ai.enabled}
 * 的**正反两面**互斥装配，保证容器里恰好有一个 {@code AiReportGenerator}。
 *
 * <p>那个范式的适用前提是：<b>"依赖可不可用"在启动时就能确定。</b>
 * 接入访客自带 key 之后这个前提没了——能不能调模型取决于**这次请求带没带 key**，
 * 是运行时的判断。硬套原来的写法只会多出一个"启动时就注定返回 501 的桩实现"，
 * 而它和真实现在运行时其实走的是同一条路（都取决于凭据），
 * 等于凭空维护两套并行的代码路径。
 *
 * <p>所以桩实现被删掉了，"没有可用凭据 → 501"这件事交给了
 * {@code AiCredentialsResolver} + 服务层在运行时判断。
 *
 * <h2>这个类现在只剩两件事</h2>
 *
 * <p>组织提示词（{@link AiPromptBuilder}），和让 {@link DeepSeekChatClient}
 * 把话带到。HTTP、超时、错误处理、响应解析全都在那个客户端里。
 */
@Component
public class DeepSeekAiReportGenerator implements AiReportGenerator {

    private final DeepSeekChatClient chatClient;
    private final AiPromptBuilder promptBuilder;

    public DeepSeekAiReportGenerator(DeepSeekChatClient chatClient,
                                     AiPromptBuilder promptBuilder) {
        this.chatClient = chatClient;
        this.promptBuilder = promptBuilder;
    }

    @Override
    public String generateReport(AiCredentials credentials, SessionResultResponse result) {
        // 人格解读是一整段散文，不需要 JSON 模式
        return chatClient.complete(
                credentials,
                promptBuilder.systemPrompt(),
                promptBuilder.userPrompt(result));
    }
}
