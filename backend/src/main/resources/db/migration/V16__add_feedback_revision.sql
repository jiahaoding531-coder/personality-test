-- 只增加反馈并发控制版本，不改变推荐评分、地点数据或历史事件。
ALTER TABLE recommendations
    ADD COLUMN feedback_revision bigint NOT NULL DEFAULT 0
    CHECK (feedback_revision >= 0);
