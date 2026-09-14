-- ============================================================
-- V5: 给测试会话加访问令牌，修复越权漏洞
-- ============================================================
--
-- 【修的是什么】
--
-- 为了让「不登录也能做测试」，V1 起就把这些端点设成了匿名可访问：
--
--     POST /api/test-sessions/{id}/answers
--     POST /api/test-sessions/{id}/submit
--     GET  /api/test-sessions/{id}/result
--     POST /api/test-sessions/{id}/ai-report
--
-- 当时的隐含假设是「知道 sessionId 就等于拥有它」。但 id 是
-- **数据库自增的连续整数**——从 1 数到 N 就拿到了全站数据。
-- 这属于 IDOR（不安全的直接对象引用，OWASP A01）。
--
-- 实测确认过两条攻击路径（修复前）：
--   1. 匿名 GET /api/test-sessions/24/result   → 200，读到了别人的画像
--   2. 匿名 POST /api/test-sessions/22/answers → 200，写进了别人未提交的会话
--   （CSRF 令牌拦不住，任何访客 GET 一次就能拿到）
--
-- 【怎么修】
--
-- 给每个会话发一个**随机 UUID 作为能力凭证**：
--   - 建会话时返回给创建者
--   - 后续所有操作必须带上它（X-Session-Token 请求头）
--   - 登录用户访问自己的会话时，凭身份即可，不需要令牌
--
-- 这叫「能力式访问控制」（capability-based access control）：
-- **持有令牌 = 拥有访问权**，而令牌是 128 位随机的，猜不到。
-- 同一个会话的 id 依然可以被猜到，但猜到了也没用。
--
-- ============================================================

-- 第一步：加列（先允许 NULL，否则已有数据行不知道填什么）
ALTER TABLE test_sessions ADD COLUMN access_token UUID;

-- 第二步：回填历史数据。
-- gen_random_uuid() 是 PostgreSQL 13+ 的内置函数，不需要装 pgcrypto 扩展。
-- 每一行会拿到各自不同的随机值——不是一个值填满全表，
-- 否则所有历史会话共用一个令牌，等于没修。
UPDATE test_sessions
SET access_token = gen_random_uuid()
WHERE access_token IS NULL;

-- 第三步：加上非空约束（此时已经没有 NULL 了）
ALTER TABLE test_sessions ALTER COLUMN access_token SET NOT NULL;

-- 第四步：唯一约束。
-- 它同时起到两个作用：
--   1. 保证不会有两条会话共用令牌
--   2. 给按令牌查找建立索引（这个查询会在每个受保护的请求上执行）
ALTER TABLE test_sessions
    ADD CONSTRAINT uq_test_sessions_access_token UNIQUE (access_token);

COMMENT ON COLUMN test_sessions.access_token IS
    '会话访问令牌。持有它即可访问该会话，用于支持匿名测试且不泄露他人数据。';
