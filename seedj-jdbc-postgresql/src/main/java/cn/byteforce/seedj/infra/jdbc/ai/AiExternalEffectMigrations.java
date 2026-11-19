package cn.byteforce.seedj.infra.jdbc.ai;

/**
 * V3：外部效果表。状态只前进，不承载 payload 原文。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class AiExternalEffectMigrations {

    private AiExternalEffectMigrations() {}

    static final String V3 =
            """
            CREATE TABLE IF NOT EXISTS agent_external_effect (
                decision_id UUID PRIMARY KEY,
                tenant_id UUID NOT NULL,
                command_type TEXT NOT NULL,
                args_hash TEXT NOT NULL,
                state TEXT NOT NULL
                    CHECK (state IN ('prepared','confirmed','failed','unknown')),
                external_ref TEXT,
                error_code TEXT,
                prepared_at TIMESTAMPTZ NOT NULL,
                completed_at TIMESTAMPTZ,
                CONSTRAINT agent_external_effect_confirmed
                    CHECK (state <> 'confirmed' OR external_ref IS NOT NULL),
                CONSTRAINT agent_external_effect_failed
                    CHECK (state <> 'failed' OR error_code IS NOT NULL)
            );
            """;
}
