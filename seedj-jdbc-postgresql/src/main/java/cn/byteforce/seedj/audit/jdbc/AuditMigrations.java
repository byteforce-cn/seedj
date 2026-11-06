package cn.byteforce.seedj.audit.jdbc;

import cn.byteforce.seedj.audit.CanonicalHasher;
import cn.byteforce.seedj.core.Migration;

import java.util.List;

/**
 * 审计链的版本化迁移。
 */
final class AuditMigrations {

    private AuditMigrations() {
    }

    static List<Migration> all() {
        return List.of(new Migration(1, "audit chain tables", """
                CREATE TABLE IF NOT EXISTS audit_entry (
                    id            VARCHAR(64)  PRIMARY KEY,
                    sequence      BIGINT       NOT NULL UNIQUE,
                    occurred_at   TIMESTAMPTZ  NOT NULL,
                    actor         VARCHAR(255) NOT NULL,
                    action        VARCHAR(255) NOT NULL,
                    resource_type VARCHAR(255) NOT NULL,
                    resource_id   VARCHAR(255),
                    payload       JSONB        NOT NULL DEFAULT '{}'::jsonb,
                    hash          CHAR(64)     NOT NULL,
                    prev_hash     CHAR(64)     NOT NULL,
                    signature     VARCHAR(1024)
                );

                CREATE INDEX IF NOT EXISTS audit_entry_occurred_idx
                    ON audit_entry (occurred_at);

                CREATE TABLE IF NOT EXISTS audit_head (
                    id        VARCHAR(16) PRIMARY KEY,
                    sequence  BIGINT      NOT NULL,
                    last_hash CHAR(64)    NOT NULL
                );

                INSERT INTO audit_head (id, sequence, last_hash)
                VALUES ('audit', 0, '%s')
                ON CONFLICT (id) DO NOTHING;
                """.formatted(CanonicalHasher.GENESIS_PREV_HASH)),
                new Migration(2, "audit v1.1 alignment", """
                        ALTER TABLE audit_entry RENAME COLUMN occurred_at TO timestamp;
                        ALTER TABLE audit_entry ADD COLUMN IF NOT EXISTS payload_digest TEXT;
                        ALTER TABLE audit_entry ALTER COLUMN id TYPE TEXT;
                        ALTER TABLE audit_entry ALTER COLUMN actor TYPE TEXT;
                        ALTER TABLE audit_entry ALTER COLUMN action TYPE TEXT;
                        ALTER TABLE audit_entry ALTER COLUMN resource_type TYPE TEXT;
                        ALTER TABLE audit_entry ALTER COLUMN resource_id TYPE TEXT;
                        ALTER TABLE audit_entry ALTER COLUMN hash TYPE TEXT;
                        ALTER TABLE audit_entry ALTER COLUMN prev_hash TYPE TEXT;

                        ALTER INDEX IF EXISTS audit_entry_occurred_idx
                            RENAME TO idx_audit_timestamp;

                        CREATE INDEX IF NOT EXISTS idx_audit_sequence ON audit_entry (sequence);
                        CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_entry (timestamp);
                        CREATE INDEX IF NOT EXISTS idx_audit_actor ON audit_entry (actor);
                        CREATE INDEX IF NOT EXISTS idx_audit_resource
                            ON audit_entry (resource_type, resource_id);

                        ALTER TABLE audit_entry ADD CONSTRAINT audit_hash_len
                            CHECK (hash ~ '^[0-9a-f]{64}$');
                        ALTER TABLE audit_entry ADD CONSTRAINT audit_prev_hash_len
                            CHECK (prev_hash ~ '^[0-9a-f]{64}$');
                        ALTER TABLE audit_entry ADD CONSTRAINT audit_payload_digest_len
                            CHECK (payload_digest IS NULL OR payload_digest ~ '^[0-9a-f]{64}$');

                        DROP RULE IF EXISTS audit_no_update ON audit_entry;
                        DROP RULE IF EXISTS audit_no_delete ON audit_entry;
                        CREATE RULE audit_no_update AS ON UPDATE TO audit_entry DO INSTEAD NOTHING;
                        CREATE RULE audit_no_delete AS ON DELETE TO audit_entry DO INSTEAD NOTHING;
                        """));
    }
}
