-- 餐饮来源只保留目标快照，不保存搜索中心、用户 GPS 或高德密钥。
CREATE TABLE restaurant_search_batches (
    result_set_id UUID PRIMARY KEY,
    search_session_id UUID NOT NULL,
    session_id BIGINT NOT NULL REFERENCES test_sessions(id) ON DELETE CASCADE,
    user_id BIGINT REFERENCES users(id),
    page INTEGER NOT NULL CHECK (page BETWEEN 1 AND 25),
    radius INTEGER NOT NULL CHECK (radius BETWEEN 1 AND 50000),
    coordinate_system VARCHAR(10) NOT NULL DEFAULT 'GCJ02' CHECK (coordinate_system = 'GCJ02'),
    distance_type VARCHAR(20) NOT NULL DEFAULT 'STRAIGHT_LINE' CHECK (distance_type = 'STRAIGHT_LINE'),
    status VARCHAR(20) NOT NULL CHECK (status IN ('SUCCESS','NO_RESULTS')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_restaurant_batches_expiry ON restaurant_search_batches(expires_at);
CREATE INDEX idx_restaurant_batches_scope ON restaurant_search_batches(session_id,search_session_id);

CREATE TABLE restaurant_search_items (
    result_set_id UUID NOT NULL REFERENCES restaurant_search_batches(result_set_id) ON DELETE CASCADE,
    poi_id VARCHAR(100) NOT NULL,
    name VARCHAR(500) NOT NULL,
    type VARCHAR(500),
    type_code VARCHAR(30),
    address VARCHAR(1000),
    latitude DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    distance_meters DOUBLE PRECISION,
    rank_no INTEGER NOT NULL CHECK (rank_no > 0),
    PRIMARY KEY(result_set_id,poi_id)
);

CREATE TABLE behavior_events (
    event_id UUID PRIMARY KEY,
    user_id BIGINT REFERENCES users(id),
    session_id BIGINT NOT NULL REFERENCES test_sessions(id) ON DELETE CASCADE,
    target_type VARCHAR(20) NOT NULL CHECK (target_type IN ('PLACE','RESTAURANT','HOTEL','ACTIVITY')),
    provider VARCHAR(20) NOT NULL CHECK (provider IN ('LOCAL','AMAP')),
    target_id VARCHAR(100),
    action VARCHAR(30) NOT NULL CHECK (action IN ('NAVIGATION_REQUESTED','EXCLUDE','NEXT_BATCH_REQUESTED','REACTION_SET','REACTION_CLEAR')),
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    schema_version INTEGER NOT NULL CHECK (schema_version = 1),
    recommendation_id BIGINT,
    recommendation_batch_no INTEGER,
    search_session_id UUID,
    result_set_id UUID,
    reaction VARCHAR(10) CHECK (reaction IN ('LIKE','DISLIKE')),
    visible_sources JSONB NOT NULL DEFAULT '[]',
    canonical_payload JSONB NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    CHECK ((action = 'NEXT_BATCH_REQUESTED' AND target_id IS NULL) OR
           (action <> 'NEXT_BATCH_REQUESTED' AND target_id IS NOT NULL)),
    CHECK ((target_type='PLACE' AND provider='LOCAL') OR (target_type='RESTAURANT' AND provider='AMAP'))
);
-- 这里刻意不对推荐/餐饮来源建外键：来源先过期，事件仍须在 90 天内保留、幂等。
-- 新事件的引用由服务在同一事务内验证，并锁住餐饮页避免与清理交错。
CREATE INDEX idx_behavior_events_retention ON behavior_events(received_at);
CREATE INDEX idx_behavior_events_user ON behavior_events(user_id,received_at);
CREATE INDEX idx_behavior_events_session ON behavior_events(session_id,received_at);
