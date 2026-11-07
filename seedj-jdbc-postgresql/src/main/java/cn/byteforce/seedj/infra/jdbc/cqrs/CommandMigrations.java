package cn.byteforce.seedj.infra.jdbc.cqrs;

import cn.byteforce.seedj.core.Migration;

import java.util.List;

/**
 * 命令记录表的版本化迁移。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class CommandMigrations {

    private CommandMigrations() {
    }

    static List<Migration> all() {
        return List.of(v1(), v2());
    }

    static Migration v1() {
        return new Migration(1, "command record table", """
                CREATE TABLE IF NOT EXISTS command_record (
                    id                  UUID           PRIMARY KEY,
                    type                VARCHAR(128)   NOT NULL,
                    issued_at           TIMESTAMPTZ(6) NOT NULL,
                    issuer              UUID           NOT NULL,
                    payload             JSONB          NOT NULL DEFAULT '{}'::jsonb,
                    idempotency_key     VARCHAR(255)   NOT NULL,
                    payload_fingerprint CHAR(64)       NOT NULL,
                    status              VARCHAR(16)    NOT NULL,
                    rejection_code      VARCHAR(16),
                    rejection_reason    TEXT,
                    event_ids           JSONB          NOT NULL DEFAULT '[]'::jsonb,
                    locator             UUID,
                    version             BIGINT,
                    created_at          TIMESTAMPTZ(6) NOT NULL,
                    lease_until         TIMESTAMPTZ(6) NOT NULL,
                    CONSTRAINT uq_command_record_idempotency UNIQUE (issuer, idempotency_key),
                    CONSTRAINT ck_command_record_status
                        CHECK (status IN ('pending', 'accepted', 'rejected')),
                    CONSTRAINT ck_command_record_fingerprint
                        CHECK (payload_fingerprint ~ '^[0-9a-f]{64}$'),
                    CONSTRAINT ck_command_record_accepted CHECK (
                        status <> 'accepted'
                        OR (locator IS NOT NULL AND version IS NOT NULL AND version >= 1)),
                    CONSTRAINT ck_command_record_rejected CHECK (
                        status <> 'rejected'
                        OR (rejection_code IS NOT NULL AND rejection_reason IS NOT NULL))
                );

                CREATE INDEX IF NOT EXISTS idx_command_record_status
                    ON command_record (status, created_at);
                CREATE INDEX IF NOT EXISTS idx_command_record_lease
                    ON command_record (status, lease_until);
                """);
    }

    /**
     * V2：事件溯源命令的聚合标识文本。V1 行 locator 非空、target_ref 为 null，
     * 仍满足放宽后的 ACCEPTED 约束，无需回填。
     *
     * @return 迁移
     */
    static Migration v2() {
        return new Migration(2, "command record target ref", """
                ALTER TABLE command_record ADD COLUMN IF NOT EXISTS target_ref VARCHAR(255);
                ALTER TABLE command_record DROP CONSTRAINT IF EXISTS ck_command_record_accepted;
                ALTER TABLE command_record ADD CONSTRAINT ck_command_record_accepted CHECK (
                    status <> 'accepted'
                    OR (version IS NOT NULL AND version >= 1
                        AND ((locator IS NOT NULL) <> (target_ref IS NOT NULL))));
                """);
    }
}
