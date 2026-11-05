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
                """.formatted(CanonicalHasher.GENESIS_PREV_HASH)));
    }
}
