package com.example.personality.domain;

/**
 * 计分单元的输入：一道题 + 用户给它的原始分。
 *
 * <p>这里刻意<b>不</b>直接用 JPA 实体 Question / Answer。原因有两个：
 * <ol>
 *   <li>ScoringService 因此不需要数据库、不需要 Spring 容器，可以直接
 *       {@code new ScoringService()} 来单元测试</li>
 *   <li>计分逻辑只关心"这题属于哪个维度、要不要反向、用户打了多少分"，
 *       不关心题干文字、创建时间这些无关字段</li>
 * </ol>
 *
 * <p>{@code record} 是 Java 16 引入的语法糖，一行等于写完了
 * private final 字段 + 构造器 + getter + equals + hashCode + toString。
 * 编译器自动生成，且不可变。适合用来做这种纯数据载体。
 */
public record ScoredItem(Dimension dimension, boolean reverseScored, int rawScore) {
}
