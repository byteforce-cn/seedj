-- HLC 节点状态表 (根据产品实际表名前缀调整 sys_)
CREATE TABLE IF NOT EXISTS sys_hybrid_state (
    node_id          VARCHAR(128) PRIMARY KEY,
    physical_micros  BIGINT       NOT NULL,
    logical          BIGINT       NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 全局序列锚点
CREATE SEQUENCE IF NOT EXISTS sys_global_seq
    AS BIGINT
    INCREMENT BY 1
    START WITH 1
    NO CYCLE;
