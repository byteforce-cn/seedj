package cn.byteforce.seedj.infra.jdbc.ai;

/**
 * V2 迁移的 DDL：准入表、扫描索引与 RLS 辅助函数。
 *
 * <p>单独放一个类是为了让 {@link AiMigrations} 保持「版本列表 + 说明」的薄结构；DDL 本身不依赖任何 Java 值。 函数体用单引号字符串（与 V1
 * 同一原因：迁移执行器按 {@code ;} 切分，不识别美元引用）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class AiAdmissionMigrations {

    private AiAdmissionMigrations() {}

    static final String V2 =
            """
            CREATE TABLE IF NOT EXISTS agent_decision_admission (
                decision_id UUID PRIMARY KEY,
                tenant_id UUID NOT NULL,
                command_type TEXT NOT NULL,
                targets JSONB NOT NULL DEFAULT '[]'::jsonb,
                args_hash TEXT NOT NULL,
                actor UUID NOT NULL,
                expires_at TIMESTAMPTZ NOT NULL,
                admitted_at TIMESTAMPTZ NOT NULL
            );

            CREATE INDEX IF NOT EXISTS agent_decision_admission_expiry
                ON agent_decision_admission (expires_at);

            CREATE INDEX IF NOT EXISTS agent_decision_pending
                ON agent_decision_record (tenant_id, record_kind, recorded_at);

            CREATE INDEX IF NOT EXISTS agent_decision_approval_pending
                ON agent_decision_record (tenant_id, recorded_at)
                WHERE record_kind = 'approval';

            CREATE OR REPLACE FUNCTION agent_admission_ok(p_command_type TEXT, p_args_hash TEXT)
            RETURNS BOOLEAN LANGUAGE SQL STABLE AS '
              SELECT EXISTS (
                SELECT 1 FROM agent_decision_admission a
                 WHERE a.decision_id =
                       nullif(current_setting(''agent.decision_id'', true), '''')::uuid
                   AND a.command_type = p_command_type
                   AND a.args_hash = p_args_hash
                   AND a.expires_at > now())
            ';
            """;
}
