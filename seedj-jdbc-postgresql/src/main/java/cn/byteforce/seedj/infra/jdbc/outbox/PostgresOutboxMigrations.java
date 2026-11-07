package cn.byteforce.seedj.infra.jdbc.outbox;

import cn.byteforce.seedj.core.Migration;
import java.util.List;

/**
 * Outbox 的版本化迁移。
 *
 * <p>DDL 以 Java 文本块交付，不依赖 classpath 上的裸 SQL 文件：版本号、checksum、 执行顺序都由 {@code JdbcMigrationRunner}
 * 统一管理。
 *
 * <p>V1 保留原始表结构；V2 把 eventbus 时代的列名改为通用 outbox 语义。 旧库按 scope {@code outbox} 重新走 V1（{@code CREATE
 * TABLE IF NOT EXISTS} 空操作） 和 V2（列重命名）。
 */
final class PostgresOutboxMigrations {

    private PostgresOutboxMigrations() {}

    static List<Migration> all() {
        return List.of(
                new Migration(
                        1,
                        "outbox tables",
                        """
                        CREATE TABLE IF NOT EXISTS outbox (
                            id            VARCHAR(36)  PRIMARY KEY,
                            event_id      VARCHAR(64)  NOT NULL UNIQUE,
                            topic         VARCHAR(255) NOT NULL,
                            partition_key VARCHAR(255),
                            sequence      BIGINT       NOT NULL,
                            event_type    VARCHAR(255) NOT NULL,
                            event_version INT          NOT NULL,
                            payload       BYTEA        NOT NULL,
                            headers       JSONB        NOT NULL DEFAULT '{}'::jsonb,
                            occurred_at   TIMESTAMPTZ  NOT NULL,
                            status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
                            attempts      INT          NOT NULL DEFAULT 0,
                            next_retry_at TIMESTAMPTZ,
                            lease_id      VARCHAR(64),
                            lease_until   TIMESTAMPTZ,
                            created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
                            CONSTRAINT outbox_partition_sequence_uk UNIQUE (partition_key, sequence)
                        );

                        CREATE INDEX IF NOT EXISTS outbox_claim_idx
                            ON outbox (status, next_retry_at);
                        CREATE INDEX IF NOT EXISTS outbox_partition_idx
                            ON outbox (partition_key, sequence);

                        CREATE TABLE IF NOT EXISTS outbox_audit (
                            id          VARCHAR(36)  PRIMARY KEY,
                            record_id   VARCHAR(36)  NOT NULL,
                            action      VARCHAR(32)  NOT NULL,
                            operated_by VARCHAR(128) NOT NULL,
                            occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now()
                        );

                        CREATE INDEX IF NOT EXISTS outbox_audit_record_idx
                            ON outbox_audit (record_id);
                        """),
                new Migration(
                        2,
                        "generic outbox columns",
                        """
                        ALTER TABLE outbox RENAME COLUMN event_id TO message_id;
                        ALTER TABLE outbox RENAME COLUMN topic TO destination;
                        ALTER TABLE outbox RENAME COLUMN event_type TO content_type;
                        ALTER TABLE outbox RENAME COLUMN event_version TO content_version;
                        """),
                new Migration(
                        3,
                        "outbox dead letters and inbox",
                        """
                        ALTER TABLE outbox ADD COLUMN IF NOT EXISTS aggregate_type VARCHAR(128);
                        ALTER TABLE outbox ADD COLUMN IF NOT EXISTS dead_at TIMESTAMPTZ;
                        ALTER TABLE outbox ADD COLUMN IF NOT EXISTS last_error VARCHAR(1024);

                        CREATE TABLE IF NOT EXISTS inbox_message (
                            consumer_group  TEXT        NOT NULL,
                            event_id        UUID        NOT NULL,
                            status          TEXT        NOT NULL DEFAULT 'processing',
                            attempts        INTEGER     NOT NULL DEFAULT 0,
                            last_error      TEXT,
                            received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                            done_at         TIMESTAMPTZ,
                            dead_at         TIMESTAMPTZ,
                            PRIMARY KEY (consumer_group, event_id)
                        );

                        CREATE INDEX IF NOT EXISTS idx_inbox_due
                            ON inbox_message (status);
                        """));
    }
}
