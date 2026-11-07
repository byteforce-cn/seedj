package cn.byteforce.seedj.infra.jdbc.workflow;

/**
 * 工作流持久化表的冻结 DDL。
 *
 * <p>表按“定义 / 实例 / 任务 / 历史 / 定时器”拆分；实例与任务是当前状态，历史是 只追加事件，定时器是到期扫描队列。JSONB 只承载 Provider 自定义详情，调用方
 * 不解析；查询列（状态、due_at、instance_id）单独建列与索引。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class WorkflowMigrations {

    /** 工作流迁移 scope。 */
    public static final String SCOPE = "workflow";

    private WorkflowMigrations() {}

    /**
     * 第一版工作流 schema。
     *
     * @return literal SQL
     */
    public static String v1() {
        return """
                CREATE TABLE IF NOT EXISTS workflow_definition (
                    namespace       TEXT        NOT NULL,
                    name            TEXT        NOT NULL,
                    major           INT         NOT NULL,
                    minor           INT         NOT NULL,
                    definition_json JSONB       NOT NULL,
                    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                    PRIMARY KEY (namespace, name, major, minor)
                );

                CREATE TABLE IF NOT EXISTS workflow_instance (
                    instance_id          TEXT        PRIMARY KEY,
                    definition_namespace TEXT        NOT NULL,
                    definition_name      TEXT        NOT NULL,
                    definition_major     INT         NOT NULL,
                    definition_minor     INT         NOT NULL,
                    status               TEXT        NOT NULL,
                    current_step         TEXT,
                    started_by           TEXT        NOT NULL,
                    started_at           TIMESTAMPTZ NOT NULL,
                    updated_at           TIMESTAMPTZ NOT NULL,
                    completed_at         TIMESTAMPTZ,
                    variables            JSONB       NOT NULL DEFAULT '{}'::jsonb,
                    failure              JSONB,
                    version              BIGINT      NOT NULL DEFAULT 1
                );
                CREATE INDEX IF NOT EXISTS idx_workflow_instance_definition
                    ON workflow_instance (definition_namespace, definition_name, status);
                CREATE INDEX IF NOT EXISTS idx_workflow_instance_started
                    ON workflow_instance (started_at);

                CREATE TABLE IF NOT EXISTS workflow_task (
                    task_id      TEXT        PRIMARY KEY,
                    instance_id  TEXT        NOT NULL
                        REFERENCES workflow_instance (instance_id),
                    step_name    TEXT        NOT NULL,
                    kind         TEXT        NOT NULL,
                    status       TEXT        NOT NULL,
                    assignments  JSONB       NOT NULL DEFAULT '[]'::jsonb,
                    form_data    JSONB       NOT NULL DEFAULT '{}'::jsonb,
                    due_at       TIMESTAMPTZ,
                    created_at   TIMESTAMPTZ NOT NULL,
                    updated_at   TIMESTAMPTZ NOT NULL,
                    completed_at TIMESTAMPTZ
                );
                CREATE INDEX IF NOT EXISTS idx_workflow_task_instance
                    ON workflow_task (instance_id, status);
                CREATE INDEX IF NOT EXISTS idx_workflow_task_due
                    ON workflow_task (due_at) WHERE status = 'PENDING';

                CREATE SEQUENCE IF NOT EXISTS workflow_history_seq START 1;

                CREATE TABLE IF NOT EXISTS workflow_history (
                    event_id    TEXT        PRIMARY KEY,
                    instance_id TEXT        NOT NULL
                        REFERENCES workflow_instance (instance_id),
                    sequence    BIGINT      NOT NULL,
                    event_type  TEXT        NOT NULL,
                    step_name   TEXT,
                    task_id     TEXT,
                    action_id   TEXT,
                    occurred_at TIMESTAMPTZ NOT NULL,
                    attributes  JSONB       NOT NULL DEFAULT '{}'::jsonb,
                    UNIQUE (instance_id, sequence)
                );
                CREATE INDEX IF NOT EXISTS idx_workflow_history_instance
                    ON workflow_history (instance_id, sequence);

                CREATE TABLE IF NOT EXISTS workflow_timer (
                    timer_id    TEXT        PRIMARY KEY,
                    instance_id TEXT        NOT NULL
                        REFERENCES workflow_instance (instance_id),
                    task_id     TEXT,
                    due_at      TIMESTAMPTZ NOT NULL,
                    status      TEXT        NOT NULL DEFAULT 'PENDING',
                    fired_at    TIMESTAMPTZ,
                    payload     JSONB       NOT NULL DEFAULT '{}'::jsonb
                );
                CREATE INDEX IF NOT EXISTS idx_workflow_timer_due
                    ON workflow_timer (due_at) WHERE status = 'PENDING';
                """;
    }
}
