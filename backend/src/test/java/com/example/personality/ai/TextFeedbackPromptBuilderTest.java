package com.example.personality.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 反馈提示词的边界测试。
 *
 * <p>提示词必须把服务端计算好的建议值交给模型，并明确禁止模型自行编数字。
 * 这是一条安全边界：模型只负责理解语义，数值决定权仍在服务端。</p>
 */
class TextFeedbackPromptBuilderTest {

    private final TextFeedbackPromptBuilder builder = new TextFeedbackPromptBuilder();

    @Test
    void promptLimitsModelToProvidedDistanceAndKnownOperations() {
        String prompt = builder.userPrompt(new FeedbackPromptContext(
                "这家太远了", "灵隐寺", 4200, 3000,
                45, 40, "想安静，预算 100 元"));

        assertThat(prompt).contains("建议的新距离上限：3000 米")
                .contains("不得自行生成其他距离数字")
                .contains("灵隐寺");
    }
}
