package cn.byteforce.seedj.infra.jdbc.im;

import cn.byteforce.seedj.core.Migration;
import java.util.List;

/**
 * IM schema。已应用的迁移不得修改；只追加新版本。
 *
 * <p>三张会话侧表放在 V1，消息表与索引放在 V2，游标表放在 V3——按 IM 的实现顺序排列，回滚时从 后往前删，不会碰到被引用方的顺序问题。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ImMigrations {

    private ImMigrations() {}

    /**
     * 全部迁移。
     *
     * @return 按版本升序的迁移
     */
    public static List<Migration> all() {
        return List.of(
                new Migration(1, "im conversations and membership", V1, ROLLBACK_V1),
                new Migration(2, "im messages", V2, ROLLBACK_V2),
                new Migration(3, "im cursors", V3, ROLLBACK_V3),
                new Migration(4, "im message retention index", V4, ROLLBACK_V4));
    }

    private static final String V1 =
            """
            CREATE SEQUENCE IF NOT EXISTS im_seq AS BIGINT START 1 INCREMENT 1 CACHE 50;

            CREATE TABLE IF NOT EXISTS im_conversation (
                id         UUID PRIMARY KEY,
                tenant_id  VARCHAR(64)  NOT NULL,
                kind       VARCHAR(16)  NOT NULL,
                title      VARCHAR(255),
                owner_id   VARCHAR(64)  NOT NULL,
                direct_key VARCHAR(160),
                created_at TIMESTAMPTZ  NOT NULL,
                updated_at TIMESTAMPTZ  NOT NULL,
                CONSTRAINT uq_im_conversation_direct UNIQUE (tenant_id, direct_key)
            );

            CREATE INDEX IF NOT EXISTS idx_im_conversation_owner
                ON im_conversation (tenant_id, owner_id);

            CREATE TABLE IF NOT EXISTS im_conversation_member (
                conversation_id UUID        NOT NULL
                    REFERENCES im_conversation (id) ON DELETE CASCADE,
                subject_id      VARCHAR(64) NOT NULL,
                joined_seq      BIGINT      NOT NULL,
                left_seq        BIGINT,
                role            VARCHAR(16) NOT NULL,
                muted_until     TIMESTAMPTZ,
                joined_at       TIMESTAMPTZ NOT NULL,
                PRIMARY KEY (conversation_id, subject_id, joined_seq),
                CONSTRAINT ck_im_member_left_after_join
                    CHECK (left_seq IS NULL OR left_seq > joined_seq)
            );

            CREATE UNIQUE INDEX IF NOT EXISTS uq_im_member_active
                ON im_conversation_member (conversation_id, subject_id)
                WHERE left_seq IS NULL;

            CREATE INDEX IF NOT EXISTS idx_im_member_subject
                ON im_conversation_member (subject_id, left_seq);

            CREATE TABLE IF NOT EXISTS im_conversation_event (
                id              UUID PRIMARY KEY,
                tenant_id       VARCHAR(64) NOT NULL,
                conversation_id UUID        NOT NULL,
                seq             BIGINT      NOT NULL,
                type            VARCHAR(32) NOT NULL,
                actor_id        VARCHAR(64) NOT NULL,
                payload         JSONB       NOT NULL DEFAULT '{}'::jsonb,
                created_at      TIMESTAMPTZ NOT NULL,
                CONSTRAINT uq_im_conversation_event_seq UNIQUE (conversation_id, seq)
            );

            CREATE INDEX IF NOT EXISTS idx_im_conversation_event_conv
                ON im_conversation_event (conversation_id, seq);
            """;

    private static final String V2 =
            """
            CREATE TABLE IF NOT EXISTS im_message (
                id              UUID PRIMARY KEY,
                tenant_id       VARCHAR(64)  NOT NULL,
                conversation_id UUID         NOT NULL,
                seq             BIGINT       NOT NULL,
                sender_id       VARCHAR(64)  NOT NULL,
                kind            VARCHAR(16)  NOT NULL,
                body            JSONB        NOT NULL DEFAULT '{}'::jsonb,
                client_msg_id   VARCHAR(64),
                created_at      TIMESTAMPTZ  NOT NULL,
                edited_at       TIMESTAMPTZ,
                recalled_at     TIMESTAMPTZ,
                CONSTRAINT uq_im_message_seq UNIQUE (conversation_id, seq),
                CONSTRAINT uq_im_message_client
                    UNIQUE (conversation_id, sender_id, client_msg_id)
            );

            CREATE INDEX IF NOT EXISTS idx_im_message_seq ON im_message (tenant_id, seq);
            CREATE INDEX IF NOT EXISTS idx_im_message_conv
                ON im_message (conversation_id, seq DESC);
            """;

    private static final String V3 =
            """
            CREATE TABLE IF NOT EXISTS im_read_cursor (
                conversation_id UUID        NOT NULL,
                subject_id      VARCHAR(64) NOT NULL,
                last_read_seq   BIGINT      NOT NULL,
                updated_at      TIMESTAMPTZ NOT NULL,
                PRIMARY KEY (conversation_id, subject_id)
            );

            CREATE TABLE IF NOT EXISTS im_sync_cursor (
                tenant_id  VARCHAR(64) NOT NULL,
                subject_id VARCHAR(64) NOT NULL,
                device_id  VARCHAR(64) NOT NULL,
                seq        BIGINT      NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL,
                PRIMARY KEY (tenant_id, subject_id, device_id)
            );
            """;

    private static final String V4 =
            """
            CREATE INDEX IF NOT EXISTS idx_im_message_created
                ON im_message (created_at);
            """;

    private static final String ROLLBACK_V1 =
            """
            DROP TABLE IF EXISTS im_conversation_event;
            DROP TABLE IF EXISTS im_conversation_member;
            DROP TABLE IF EXISTS im_conversation;
            DROP SEQUENCE IF EXISTS im_seq;
            """;

    private static final String ROLLBACK_V2 = "DROP TABLE IF EXISTS im_message";

    private static final String ROLLBACK_V3 =
            """
            DROP TABLE IF EXISTS im_sync_cursor;
            DROP TABLE IF EXISTS im_read_cursor;
            """;

    private static final String ROLLBACK_V4 = "DROP INDEX IF EXISTS idx_im_message_created";
}
