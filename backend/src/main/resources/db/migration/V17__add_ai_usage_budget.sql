-- 仅保存全局请求及保守 token 预留计数，不保存用户原话、GPS、Key 或模型响应。
CREATE TABLE ai_usage_budget (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    budget_day DATE NOT NULL,
    daily_requests BIGINT NOT NULL DEFAULT 0 CHECK (daily_requests >= 0),
    daily_reserved_tokens BIGINT NOT NULL DEFAULT 0 CHECK (daily_reserved_tokens >= 0),
    total_requests BIGINT NOT NULL DEFAULT 0 CHECK (total_requests >= 0),
    total_reserved_tokens BIGINT NOT NULL DEFAULT 0 CHECK (total_reserved_tokens >= 0)
);
INSERT INTO ai_usage_budget (id, budget_day) VALUES (1, DATE '1970-01-01');
