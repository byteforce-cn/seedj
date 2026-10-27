-- 事件脊柱辅助表（部署侧辅助表，不改 eis 契约 DDL；依据 ADR-0004 D3）
-- 语义约定见 docs/adr/0005-eis-domain-core.md：
--   * evt_stream 是流的版本权威（乐观锁基准），evt_log 只追加；
--   * global_seq 是全局顺序（消费位点），stream_version 是流内顺序（重放）；
--   * tenant_id 为租户隔离列，租户必填口径由配置项 tenant-required 决定。

CREATE TABLE IF NOT EXISTS evt_stream (
    stream_id       VARCHAR(255) PRIMARY KEY,
    aggregate_type  VARCHAR(255) NOT NULL,
    tenant_id       BIGINT,
    current_version BIGINT       NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE IF NOT EXISTS evt_log (
    global_seq     BIGSERIAL PRIMARY KEY,
    event_id       UUID         NOT NULL UNIQUE,
    stream_id      VARCHAR(255) NOT NULL,
    aggregate_type VARCHAR(255) NOT NULL,
    event_type     VARCHAR(160) NOT NULL,
    schema_version SMALLINT     NOT NULL DEFAULT 1,
    stream_version BIGINT       NOT NULL,
    tenant_id      BIGINT,
    payload        JSONB        NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    recorded_at    TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_evt_log_stream_version ON evt_log (stream_id, stream_version);
CREATE INDEX IF NOT EXISTS idx_evt_log_stream ON evt_log (stream_id, stream_version);
CREATE INDEX IF NOT EXISTS idx_evt_log_tenant ON evt_log (tenant_id, global_seq);
