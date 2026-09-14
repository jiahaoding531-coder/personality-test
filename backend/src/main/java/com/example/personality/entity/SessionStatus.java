package com.example.personality.entity;

/**
 * 测试会话的状态。
 *
 * <p>只有两个状态，但它是 <b>submit 幂等性的第一道防线</b>：
 * 已提交的会话不能再次提交，否则会重复计分、覆盖已有画像。
 *
 * <p>第二道防线是 {@code personality_profiles.session_id} 上的唯一约束。
 * 应用层判断可能因为并发请求而失效（两个请求同时读到 IN_PROGRESS），
 * 这时数据库的唯一约束会兜底，让其中一个事务失败。
 */
public enum SessionStatus {

    /** 测试进行中，可以继续答题或提交。 */
    IN_PROGRESS,

    /** 已提交并完成计分，不可再次提交。 */
    SUBMITTED
}
