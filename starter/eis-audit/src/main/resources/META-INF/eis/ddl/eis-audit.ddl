-- ============================================================================
-- eis-audit.ddl — EIS 审计表（starter-eis-audit 随模块分发的运行时物化）
-- 源：eis 仓库 05-audit-contract/audit.ddl（冻结契约）＋ 锚表（实现细节）
-- 目标：PostgreSQL 16+
-- 前置：已执行 eis-core.ddl（eis_id / eis_timestamp 域）
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. 审计表（契约列原样；append-only：禁止 UPDATE / DELETE）
-- ----------------------------------------------------------------------------
CREATE TABLE audit_entry (
    id              UUID          NOT NULL,
    sequence        BIGSERIAL     NOT NULL,      -- 由锚表显式分配（连续无缺口）；此处保留列类型
    timestamp       TIMESTAMPTZ(6) NOT NULL,     -- UTC 微秒
    actor           UUID          NOT NULL,
    action          TEXT          NOT NULL,
    resource_type   TEXT          NOT NULL,
    resource_id     UUID,
    payload         JSONB         NOT NULL,
    hash            TEXT          NOT NULL,
    prev_hash       TEXT          NOT NULL,
    signature       TEXT,                        -- 可选 CA 签名（不参与 v1 链验证）

    CONSTRAINT pk_audit_entry      PRIMARY KEY (id),
    CONSTRAINT audit_sequence_uq   UNIQUE (sequence),
    CONSTRAINT audit_hash_len      CHECK (hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT audit_prev_hash_len CHECK (prev_hash ~ '^[0-9a-f]{64}$')
);

CREATE RULE audit_no_update AS ON UPDATE TO audit_entry DO INSTEAD NOTHING;
CREATE RULE audit_no_delete AS ON DELETE TO audit_entry DO INSTEAD NOTHING;

CREATE INDEX idx_audit_sequence ON audit_entry (sequence);
CREATE INDEX idx_audit_timestamp ON audit_entry (timestamp);
CREATE INDEX idx_audit_actor ON audit_entry (actor);
CREATE INDEX idx_audit_resource ON audit_entry (resource_type, resource_id);

-- ----------------------------------------------------------------------------
-- 2. 锚表（实现细节）：单行链头；序列由本行 UPDATE … RETURNING 串行分配
--    ——不复用 audit_entry.sequence 的 BIGSERIAL 缺省分配（并发/回滚会留缺口）
-- ----------------------------------------------------------------------------
CREATE TABLE eis_audit_head (
    id            INTEGER NOT NULL,
    last_sequence BIGINT  NOT NULL,
    last_hash     TEXT    NOT NULL,

    CONSTRAINT pk_eis_audit_head   PRIMARY KEY (id),
    CONSTRAINT ck_eis_audit_head   CHECK (id = 1),
    CONSTRAINT ck_eis_audit_head_hash_len CHECK (last_hash ~ '^[0-9a-f]{64}$')
);

INSERT INTO eis_audit_head (id, last_sequence, last_hash)
VALUES (1, 0, repeat('0', 64))
ON CONFLICT (id) DO NOTHING;
