package com.example.personality.dto;

import com.example.personality.entity.Question;

/**
 * 单道题目的 API 响应。
 *
 * <p><b>⚠️ 注意这里<b>故意没有</b> reverseScored 字段。</b>
 *
 * <p>数据库里 questions 表确实有 reverse_scored 列，但接口不返回它。
 * 原因：一旦前端拿到了"哪些题是反向的"，任何人都能在浏览器 Network 面板里
 * 看到这个标记，然后有针对性地答题来操纵结果。
 *
 * <p>更普遍的原则是：<b>数据库实体 ≠ API 响应对象</b>。
 * 直接把实体序列化成 JSON 是最常见的错误之一——它会泄露内部字段
 * （密码哈希、内部标记、软删除标记等），而且一旦表结构改动就会
 * 破坏已经发布的 API 契约。中间隔一层 DTO，两边的演进就互相独立了。
 */
public record QuestionResponse(
        Long id,
        String content,
        String dimension,
        String dimensionLabel,
        int sortOrder
) {

    public static QuestionResponse from(Question question) {
        return new QuestionResponse(
                question.getId(),
                question.getContent(),
                // 维度的英文标识（如 OPENNESS / NATURE）
                question.getDimension(),
                // 中文名由实体按量表解析，DTO 不重复这个判断
                question.getDimensionLabel(),
                question.getSortOrder()
        );
    }
}
