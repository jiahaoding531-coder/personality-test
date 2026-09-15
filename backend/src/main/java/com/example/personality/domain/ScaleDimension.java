package com.example.personality.domain;

/**
 * 量表维度——两套量表（人格 / 旅行偏好）的维度枚举共同的<b>最小契约</b>。
 *
 * <h2>为什么需要这个接口</h2>
 *
 * <p>计分流程（{@code ScoringService}）对两套量表是<b>完全一样</b>的：
 * 按维度分组求和 → 归一化到 0~100。差别只在"维度叫什么名字"。
 * 但 {@link Dimension} 和 {@link TravelDimension} 原本没有任何公共父类型，
 * 计分器的签名就被写死成了人格那一套，旅行量表接不进去。
 *
 * <p>加上这个接口之后，计分器的签名可以写成：
 * <pre>
 *   &lt;D extends Enum&lt;D&gt; &amp; ScaleDimension&gt; Map&lt;D, DimensionScore&lt;D&gt;&gt; score(...)
 * </pre>
 * 也就是"任意一套枚举都行，只要你每个常量能说出自己的中文名"。
 *
 * <h2>⚠️ 为什么不干脆把两个枚举合并成一个</h2>
 *
 * <p>因为它们的<b>语义层级不同</b>：人格维度描述"你是个什么样的人"，
 * 旅行维度描述"这次旅行你想要什么"。合并之后会出现
 * "给一个人格维度算旅行属性"这种没有意义的操作，
 * 而且 {@code PersonalityProfile} 那 5 个固定列也塞不下 8 个维度。
 *
 * <p><b>加接口是"共用算法"，合枚举是"混同概念"——这两件事不一样。</b>
 *
 * <h2>为什么接口里只有 label()</h2>
 *
 * <p>因为只有它被计分器用到了（拼错误信息："维度「自然风光」没有任何作答"）。
 * 接口要尽可能小——每往里加一个方法，两套枚举就被迫多实现一个，
 * 而多出来的约束未必对两边都有意义。
 *
 * <p>注意枚举常量还实现了 {@code Enum} 自带的方法（{@code name()}、{@code ordinal()}…），
 * 所以泛型边界写的是 {@code Enum<D> & ScaleDimension}——两个约束缺一不可：
 * 前者让 {@code EnumMap} 和 {@code values()} 可用，后者让 {@code label()} 可用。
 */
public interface ScaleDimension {

    /** 中文展示名，用于 API 响应、错误信息和 AI 提示词。 */
    String label();
}
