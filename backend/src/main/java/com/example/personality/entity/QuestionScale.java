package com.example.personality.entity;

/**
 * 项目里的两套量表。
 *
 * <p>对应 {@code questions.scale} 和 {@code test_sessions.scale} 两列。
 *
 * <p>两套量表共用同一套「出题 → 作答 → 计分 → 存画像」的流程，
 * 只是维度集合不同：
 * <ul>
 *   <li>{@link #PERSONALITY} —— 5 个维度，每维度 4 题，有反向计分</li>
 *   <li>{@link #TRAVEL} —— 8 个维度，每维度 1 题，无反向计分</li>
 * </ul>
 */
public enum QuestionScale {

    /** 人格测试。结果存进 {@code personality_profiles}。 */
    PERSONALITY("人格测试"),

    /** 旅行偏好测试。结果存进 {@code travel_profiles}。 */
    TRAVEL("旅行偏好测试");

    private final String label;

    QuestionScale(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
