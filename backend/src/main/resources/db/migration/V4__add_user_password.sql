-- ============================================================
-- V4: 给 users 表加密码列，启用真实的用户体系
-- ============================================================
-- 至此 users 表才真正被使用——V1 建好它的时候项目还不做登录，
-- V0.1~V0.3 里 test_sessions.user_id 一直是 NULL。
--
-- 【为什么列名是 password_hash 而不是 password】
--   名字本身就在提醒调用方："这里存的是哈希，不是明文"。
--   叫 password 的列被人塞明文的情况，在真实项目里非常常见。
--
-- 【长度为什么是 100】
--   BCrypt 的哈希固定是 60 个字符（$2a$10$ + 22 字符盐 + 31 字符摘要）。
--   留到 100 是为了将来换算法（Argon2 的哈希更长）时不用再改表结构。
--
-- 【为什么要先给 DEFAULT 再 DROP DEFAULT】
--   PostgreSQL 里 `ADD COLUMN x NOT NULL` 在表**非空**时会失败，
--   因为已有行不知道该填什么。
--   先给一个默认值让 ALTER 通过，再立刻把默认值删掉——
--   这样将来的 INSERT 如果不指定 password_hash 仍然会报错，
--   不会悄悄存进一个空字符串当密码。
--
--   本项目此刻 users 表是空的，直接加 NOT NULL 也能成功；
--   但这个写法在所有情况下都对，值得记住。
-- ============================================================

ALTER TABLE users ADD COLUMN password_hash VARCHAR(100) NOT NULL DEFAULT '';

ALTER TABLE users ALTER COLUMN password_hash DROP DEFAULT;

COMMENT ON COLUMN users.password_hash IS 'BCrypt 哈希后的密码，固定 60 字符。绝不存明文。';
