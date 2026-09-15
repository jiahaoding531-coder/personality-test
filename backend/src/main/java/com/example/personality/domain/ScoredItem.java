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
 *
 * <h2>类型参数 {@code D} 是什么</h2>
 *
 * <p>维度可能是人格维度（{@link Dimension}），也可能是旅行维度（{@link TravelDimension}），
 * 取决于这次计分的是哪套量表。写成泛型之后，同一个计分器就能吃两种输入。
 *
 * <p>边界 {@code D extends Enum<D> & ScaleDimension} 有两层含义，缺一不可：
 * <ul>
 *   <li>{@code Enum<D>} —— 保证它是枚举（计分器要用 {@code EnumMap} 按维度分组）</li>
 *   <li>{@code ScaleDimension} —— 保证它说得出自己的中文名（错误信息要用）</li>
 * </ul>
 *
 * <p>⚠️ 那个看起来像自己引用自己的 {@code D extends Enum<D>} 是 Java 的固定写法，
 * 意思是"D 是一个枚举，且这个枚举的常量类型就是 D 本身"。照抄即可，不用纠结。
 */
public record ScoredItem<D extends Enum<D> & ScaleDimension>(
        D dimension,
        boolean reverseScored,
        int rawScore
) {
}
