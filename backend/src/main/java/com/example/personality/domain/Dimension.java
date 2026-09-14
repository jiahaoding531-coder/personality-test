package com.example.personality.domain;

/**
 * 人格模型的 5 个维度。
 *
 * <p>这是整个项目最基础的"字典"——数据库 questions.dimension 列、API 返回的
 * key、AI 提示词里的维度名，全部以这个枚举为准。
 *
 * <p>为什么用枚举而不是散落的字符串：如果哪一天要把"情绪稳定性"改名成"抗压能力"，
 * 只需要改这里的 label 一处，编译器会帮你检查所有引用。
 */
public enum Dimension {

    OPENNESS("开放性"),
    EXTRAVERSION("外向性"),
    CONSCIENTIOUSNESS("责任心"),
    AGREEABLENESS("宜人性"),
    EMOTIONAL_STABILITY("情绪稳定性");

    private final String label;

    Dimension(String label) {
        this.label = label;
    }

    /** 中文展示名，用于 API 响应和 AI 提示词。 */
    public String label() {
        return label;
    }
}
