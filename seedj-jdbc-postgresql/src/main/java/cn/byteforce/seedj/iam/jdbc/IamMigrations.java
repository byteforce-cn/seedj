package cn.byteforce.seedj.iam.jdbc;

import cn.byteforce.seedj.core.Migration;

import java.util.List;

/** Versioned IAM schema. Never edit an applied migration; add a new version. */
public final class IamMigrations {

    private IamMigrations() {
    }

    public static List<Migration> all() {
        return List.of(new Migration(1, "iam core schema", V1),
                new Migration(2, "iam authz bindings", V2));
    }

    private static final String V2 = """
            CREATE TABLE IF NOT EXISTS iam_binding (
                id            VARCHAR(64) PRIMARY KEY,
                principal     VARCHAR(64) NOT NULL,
                role          VARCHAR(64) NOT NULL,
                scope         JSONB       NOT NULL DEFAULT '{}'::jsonb,
                valid_from    TIMESTAMPTZ NOT NULL,
                valid_to      TIMESTAMPTZ,
                recorded_at   TIMESTAMPTZ NOT NULL,
                superseded_at TIMESTAMPTZ
            );

            CREATE INDEX IF NOT EXISTS idx_iam_binding_principal
                ON iam_binding (principal, valid_from, valid_to);

            CREATE TABLE IF NOT EXISTS iam_proxy (
                id            VARCHAR(64) PRIMARY KEY,
                principal     VARCHAR(64) NOT NULL,
                agent         VARCHAR(64) NOT NULL,
                scope         JSONB       NOT NULL DEFAULT '{}'::jsonb,
                valid_from    TIMESTAMPTZ NOT NULL,
                valid_to      TIMESTAMPTZ,
                recorded_at   TIMESTAMPTZ NOT NULL,
                superseded_at TIMESTAMPTZ
            );

            CREATE INDEX IF NOT EXISTS idx_iam_proxy_agent
                ON iam_proxy (agent, valid_from, valid_to);
            """;

    private static final String V1 = """
            CREATE TABLE iam_user (
                id                   VARCHAR(64)  PRIMARY KEY,
                tenant_id            VARCHAR(64)  NOT NULL,
                user_name            VARCHAR(255) NOT NULL,
                user_name_normalized VARCHAR(255) NOT NULL,
                external_id          VARCHAR(255),
                display_name         VARCHAR(255),
                email                VARCHAR(320),
                active               BOOLEAN      NOT NULL DEFAULT TRUE,
                attributes           JSONB        NOT NULL DEFAULT '{}'::jsonb,
                version              BIGINT       NOT NULL DEFAULT 0,
                created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
                updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
                CONSTRAINT uq_iam_user_login UNIQUE (tenant_id, user_name_normalized)
            );

            CREATE TABLE iam_user_credential (
                user_id            VARCHAR(64) PRIMARY KEY REFERENCES iam_user(id) ON DELETE CASCADE,
                password_hash      VARCHAR(255) NOT NULL,
                password_changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
            );

            CREATE TABLE iam_group (
                id           VARCHAR(64)  PRIMARY KEY,
                tenant_id    VARCHAR(64)  NOT NULL,
                display_name VARCHAR(255) NOT NULL,
                external_id  VARCHAR(255),
                version      BIGINT       NOT NULL DEFAULT 0,
                created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
                updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
                CONSTRAINT uq_iam_group_name UNIQUE (tenant_id, display_name)
            );

            CREATE TABLE iam_group_member (
                group_id VARCHAR(64) NOT NULL REFERENCES iam_group(id) ON DELETE CASCADE,
                user_id  VARCHAR(64) NOT NULL REFERENCES iam_user(id) ON DELETE CASCADE,
                PRIMARY KEY (group_id, user_id)
            );

            CREATE TABLE iam_role (
                id          VARCHAR(64)  PRIMARY KEY,
                tenant_id   VARCHAR(64)  NOT NULL,
                code        VARCHAR(128) NOT NULL,
                name        VARCHAR(255) NOT NULL,
                description VARCHAR(1024),
                parent_id   VARCHAR(64) REFERENCES iam_role(id) ON DELETE SET NULL,
                built_in    BOOLEAN      NOT NULL DEFAULT FALSE,
                version     BIGINT       NOT NULL DEFAULT 0,
                CONSTRAINT uq_iam_role_code UNIQUE (tenant_id, code)
            );

            CREATE TABLE iam_permission (
                tenant_id   VARCHAR(64)  NOT NULL,
                code        VARCHAR(128) NOT NULL,
                resource    VARCHAR(128) NOT NULL,
                action      VARCHAR(64)  NOT NULL,
                description VARCHAR(1024),
                PRIMARY KEY (tenant_id, code)
            );

            CREATE TABLE iam_role_permission (
                role_id         VARCHAR(64)  NOT NULL REFERENCES iam_role(id) ON DELETE CASCADE,
                permission_code VARCHAR(128) NOT NULL,
                PRIMARY KEY (role_id, permission_code)
            );

            CREATE TABLE iam_user_role (
                user_id     VARCHAR(64) NOT NULL REFERENCES iam_user(id) ON DELETE CASCADE,
                role_id     VARCHAR(64) NOT NULL REFERENCES iam_role(id) ON DELETE CASCADE,
                assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                assigned_by VARCHAR(255),
                PRIMARY KEY (user_id, role_id)
            );

            CREATE TABLE iam_group_role (
                group_id VARCHAR(64) NOT NULL REFERENCES iam_group(id) ON DELETE CASCADE,
                role_id  VARCHAR(64) NOT NULL REFERENCES iam_role(id) ON DELETE CASCADE,
                PRIMARY KEY (group_id, role_id)
            );

            CREATE TABLE iam_policy (
                id         VARCHAR(64)  PRIMARY KEY,
                tenant_id  VARCHAR(64)  NOT NULL,
                name       VARCHAR(255) NOT NULL,
                effect     VARCHAR(16)  NOT NULL,
                priority   INT          NOT NULL DEFAULT 0,
                actions    JSONB        NOT NULL,
                resources  JSONB        NOT NULL,
                condition  JSONB        NOT NULL,
                enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
                version    BIGINT       NOT NULL DEFAULT 0,
                created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
                updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
            );

            CREATE TABLE iam_authz_epoch (
                tenant_id VARCHAR(64) PRIMARY KEY,
                epoch     BIGINT      NOT NULL DEFAULT 0
            );

            CREATE TABLE iam_totp (
                user_id           VARCHAR(64)  PRIMARY KEY REFERENCES iam_user(id) ON DELETE CASCADE,
                tenant_id         VARCHAR(64)  NOT NULL,
                key_name          VARCHAR(128) NOT NULL,
                secret_ciphertext TEXT         NOT NULL,
                confirmed         BOOLEAN      NOT NULL DEFAULT FALSE,
                last_used_step    BIGINT       NOT NULL DEFAULT -1,
                created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
                confirmed_at      TIMESTAMPTZ
            );

            CREATE TABLE iam_recovery_code (
                user_id    VARCHAR(64)  NOT NULL REFERENCES iam_user(id) ON DELETE CASCADE,
                code_hash  VARCHAR(255) NOT NULL,
                used       BOOLEAN      NOT NULL DEFAULT FALSE,
                created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
                PRIMARY KEY (user_id, code_hash)
            );

            CREATE TABLE iam_mfa_challenge (
                id           VARCHAR(64)  PRIMARY KEY,
                user_id      VARCHAR(64)  NOT NULL,
                client_id    VARCHAR(128) NOT NULL,
                device_id    VARCHAR(255),
                token_hash   VARCHAR(64)  NOT NULL UNIQUE,
                expires_at   TIMESTAMPTZ  NOT NULL,
                consumed     BOOLEAN      NOT NULL DEFAULT FALSE,
                attempts     INT          NOT NULL DEFAULT 0,
                max_attempts INT          NOT NULL DEFAULT 5,
                created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
            );

            CREATE TABLE iam_audit (
                id            VARCHAR(64)  PRIMARY KEY,
                tenant_id     VARCHAR(64)  NOT NULL,
                actor_id      VARCHAR(64),
                action        VARCHAR(128) NOT NULL,
                resource_type VARCHAR(64),
                resource_id   VARCHAR(128),
                outcome       VARCHAR(32)  NOT NULL,
                reason        VARCHAR(1024),
                occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
                details       JSONB        NOT NULL DEFAULT '{}'::jsonb
            );

            CREATE TABLE iam_login_attempt (
                id          BIGSERIAL PRIMARY KEY,
                tenant_id   VARCHAR(64)  NOT NULL,
                username    VARCHAR(255) NOT NULL,
                client_id   VARCHAR(128),
                device_id   VARCHAR(255),
                ip          VARCHAR(64),
                success     BOOLEAN      NOT NULL,
                reason      VARCHAR(255),
                occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now()
            );

            CREATE TABLE iam_jwk (
                kid         VARCHAR(64) PRIMARY KEY,
                algorithm   VARCHAR(32) NOT NULL,
                public_jwk  TEXT        NOT NULL,
                private_jwk TEXT,
                active      BOOLEAN     NOT NULL DEFAULT FALSE,
                created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                retired_at  TIMESTAMPTZ
            );

            CREATE INDEX idx_iam_user_tenant ON iam_user(tenant_id);
            CREATE INDEX idx_iam_audit_tenant_time ON iam_audit(tenant_id, occurred_at DESC);
            CREATE INDEX idx_iam_login_attempt_user_time ON iam_login_attempt(tenant_id, username, occurred_at DESC);
            CREATE INDEX idx_iam_policy_tenant ON iam_policy(tenant_id, enabled, priority DESC);
            """;
}
