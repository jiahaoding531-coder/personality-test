-- ============================================================
-- V8: 会话加 scale，区分这次测的是哪套量表
-- ============================================================
-- 这是 V6「题库支持多套量表」的另一半：
--   V6 让 **题目** 知道自己属于哪套量表（questions.scale）
--   V8 让 **会话** 知道自己这次测的是哪套（test_sessions.scale）
-- 两边都齐了，submit 时才知道该算人格画像还是旅行画像。
--
-- 【为什么单独一个迁移，而不是并进 V6】
-- V6 已经在开发库里执行过了。Flyway 会校验已执行迁移的校验和，
-- 事后改 V6 会让所有已迁移的库启动失败（checksum mismatch）。
-- 迁移一旦跑过就冻结——这是用 Flyway 必须接受的纪律，
-- 需要补东西就新开一个版本号。
--
-- 【为什么复用 test_sessions 而不是新建一张旅行会话表】
-- 「创建会话 → 分次答题 → 提交计分」这个流程两套量表完全一样，
-- 差异只在提交时算哪种画像。复用顺带白拿了这些能力：
-- 中断续答、幂等提交（409）、历史记录，
-- 以及那 60 多个集成测试覆盖过的安全校验（会话访问令牌）。
-- ============================================================

ALTER TABLE test_sessions ADD COLUMN scale VARCHAR(20) NOT NULL DEFAULT 'PERSONALITY';

ALTER TABLE test_sessions ADD CONSTRAINT ck_test_sessions_scale
    CHECK (scale IN ('PERSONALITY', 'TRAVEL'));

COMMENT ON COLUMN test_sessions.scale IS '这次会话用的是哪套量表，决定 submit 时算哪种画像';
