-- ============================================================================
-- it-schema.ddl — 集成套件专用测试对象表（非契约、仅测试用）
-- 结构模拟 schema-compiler 生成物（core.ddl 模板 + 领域列）
-- ============================================================================

CREATE TABLE it_widget (
    id              eis_id NOT NULL,
    version         eis_version NOT NULL DEFAULT 1,
    valid_from      eis_timestamp NOT NULL,
    valid_to        eis_timestamp,
    recorded_at     eis_timestamp NOT NULL,
    superseded_at   eis_timestamp,
    created_by      eis_id NOT NULL,
    created_at      eis_timestamp NOT NULL,
    updated_by      eis_id,
    updated_at      eis_timestamp,
    name            TEXT NOT NULL,
    active          BOOLEAN NOT NULL,
    ref             UUID,
    meta            JSONB,
    tags            TEXT[],
    hire_date       DATE,
    attempts        BIGINT,
    amount          NUMERIC(20,2),
    section_ids     UUID[],

    CONSTRAINT pk_it_widget            PRIMARY KEY (id, recorded_at),
    CONSTRAINT valid_range    CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT recorded_range CHECK (superseded_at IS NULL OR superseded_at > recorded_at)
);

CREATE UNIQUE INDEX uq_it_widget_current ON it_widget (id) WHERE superseded_at IS NULL;
CREATE INDEX idx_it_widget_valid_time    ON it_widget (valid_from, valid_to);
CREATE INDEX idx_it_widget_recorded_time ON it_widget (recorded_at, superseded_at);
