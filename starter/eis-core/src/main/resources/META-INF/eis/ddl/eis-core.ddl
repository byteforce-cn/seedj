-- ============================================================================
-- eis-core.ddl — EIS 核心运行时表（starter-eis-core 随模块分发的运行时物化）
-- 源：eis 仓库 01-data-contract/core.ddl（冻结契约）＋ 部署侧辅助表（实现细节）
-- 目标：PostgreSQL 16+；执行前置：无（本文件自含域定义）
-- 说明：本文件不包含 business_object 模板（各域对象表由 schema-compiler 生成，
--       例如 build/ddl/iam.ddl）；也不包含 audit_entry（属 starter-eis-audit）。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 0. 域定义（core.ddl §1）
-- ----------------------------------------------------------------------------
CREATE DOMAIN eis_id        AS UUID;
CREATE DOMAIN eis_version   AS BIGINT CHECK (VALUE >= 1);
CREATE DOMAIN eis_timestamp AS TIMESTAMPTZ(6);

COMMENT ON DOMAIN eis_id        IS 'UUIDv7：全局唯一、时间有序';
COMMENT ON DOMAIN eis_version   IS '乐观锁版本号：每次更新 +1';
COMMENT ON DOMAIN eis_timestamp IS 'UTC 微秒时间戳（TIMESTAMPTZ(6)）';

-- ----------------------------------------------------------------------------
-- 1. 命令表（core.ddl §4，契约列原样）
-- ----------------------------------------------------------------------------
CREATE TABLE command (
    id               eis_id        NOT NULL,
    type             TEXT          NOT NULL,
    issued_at        eis_timestamp NOT NULL,
    issuer           eis_id        NOT NULL,
    targets          UUID[]        NOT NULL DEFAULT '{}',
    payload          JSONB         NOT NULL,
    idempotency_key  TEXT          NOT NULL,
    status           TEXT          NOT NULL DEFAULT 'pending',
    rejection_reason TEXT,

    CONSTRAINT pk_command                 PRIMARY KEY (id),
    CONSTRAINT command_type_format        CHECK (type ~ '^[a-z][a-z0-9_.]*$'),
    CONSTRAINT command_status_check       CHECK (status IN ('pending', 'accepted', 'rejected')),
    CONSTRAINT command_idempotency_key_uq UNIQUE (idempotency_key)
);

CREATE INDEX idx_command_status ON command (status);

-- ----------------------------------------------------------------------------
-- 2. 事件表（core.ddl §3，契约列原样；append-only）
-- ----------------------------------------------------------------------------
CREATE TABLE event (
    id               eis_id        NOT NULL,
    type             TEXT          NOT NULL,
    occurred_at      eis_timestamp NOT NULL,
    recorded_at      eis_timestamp NOT NULL,
    actor            eis_id        NOT NULL,
    subjects         UUID[]        NOT NULL DEFAULT '{}',
    payload          JSONB         NOT NULL,
    idempotency_key  TEXT          NOT NULL,
    version          INTEGER       NOT NULL DEFAULT 1,

    CONSTRAINT pk_event                PRIMARY KEY (id),
    CONSTRAINT event_type_format       CHECK (type ~ '^[a-z][a-z0-9_.]*$'),
    CONSTRAINT event_idempotency_key_uq UNIQUE (idempotency_key),
    CONSTRAINT event_recorded_after_occurred CHECK (recorded_at >= occurred_at)
);

CREATE INDEX idx_event_type_occurred ON event (type, occurred_at);
CREATE INDEX idx_event_actor         ON event (actor);
CREATE INDEX idx_event_subjects      ON event USING GIN (subjects);

CREATE RULE event_no_update AS ON UPDATE TO event DO INSTEAD NOTHING;
CREATE RULE event_no_delete AS ON DELETE TO event DO INSTEAD NOTHING;

-- ----------------------------------------------------------------------------
-- 3. 视图表（core.ddl §5，契约列原样；读模型可重复构建）
-- ----------------------------------------------------------------------------
CREATE TABLE view (
    id              eis_id        NOT NULL,
    view_type       TEXT          NOT NULL,
    subject         eis_id,
    data            JSONB         NOT NULL,
    as_of           eis_timestamp NOT NULL,
    recorded_as_of  eis_timestamp NOT NULL,
    computed_at     eis_timestamp NOT NULL,
    source_events   UUID[]        NOT NULL DEFAULT '{}',
    version         eis_version   NOT NULL DEFAULT 1,

    CONSTRAINT pk_view PRIMARY KEY (id, as_of, recorded_as_of)
);

CREATE INDEX idx_view_type_subject ON view (view_type, subject);
CREATE INDEX idx_view_bitemporal   ON view (view_type, as_of, recorded_as_of);

-- ----------------------------------------------------------------------------
-- 4. 部署侧辅助表（实现细节；不改契约文件）
--    eis_idempotency     幂等键 → 内容指纹（SHA-256）+ 命令 ID（契约 command 表无指纹列）
--    eis_command_event   命令 → 事件关联（保序；契约 event 表无 command_id 列）
--    eis_command_rejection 拒绝错误码（契约 command 表仅有 rejection_reason；
--                        GET /commands/{id} 需回传 code）
--    eis_command_created 受理结果的主对象指针 created（契约 command 表无该列；
--                        幂等重放/冲突比对/终态查询需回传首次结果的 created）
--    eis_outbox          事务内 outbox（真相源；分发状态回填）
-- ----------------------------------------------------------------------------
CREATE TABLE eis_idempotency (
    idempotency_key TEXT   NOT NULL,
    fingerprint     TEXT   NOT NULL,
    command_id      eis_id NOT NULL,

    CONSTRAINT pk_eis_idempotency PRIMARY KEY (idempotency_key)
);

CREATE TABLE eis_command_event (
    command_id eis_id  NOT NULL,
    event_id   eis_id  NOT NULL,
    position   INTEGER NOT NULL,

    CONSTRAINT pk_eis_command_event PRIMARY KEY (command_id, event_id)
);

CREATE TABLE eis_command_rejection (
    command_id     eis_id NOT NULL,
    rejection_code TEXT   NOT NULL,

    CONSTRAINT pk_eis_command_rejection PRIMARY KEY (command_id)
);

CREATE TABLE eis_command_created (
    command_id  eis_id NOT NULL,
    object_type TEXT   NOT NULL,
    object_id   eis_id NOT NULL,

    CONSTRAINT pk_eis_command_created PRIMARY KEY (command_id)
);

CREATE TABLE eis_outbox (
    id            eis_id        NOT NULL,
    event_id      eis_id        NOT NULL,
    topic         TEXT          NOT NULL,
    payload       JSONB         NOT NULL,
    created_at    eis_timestamp NOT NULL,
    dispatched_at eis_timestamp,

    CONSTRAINT pk_eis_outbox PRIMARY KEY (id)
);

CREATE INDEX idx_eis_outbox_undispatched ON eis_outbox (created_at) WHERE dispatched_at IS NULL;
