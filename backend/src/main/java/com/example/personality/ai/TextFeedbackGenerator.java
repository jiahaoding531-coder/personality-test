package com.example.personality.ai;

/** 将自然语言推荐反馈翻译成可验证的结构化协议。 */
public interface TextFeedbackGenerator {

    TextFeedbackResult interpret(AiCredentials credentials, FeedbackPromptContext context);
}
