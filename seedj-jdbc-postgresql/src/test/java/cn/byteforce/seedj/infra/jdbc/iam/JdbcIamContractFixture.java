package cn.byteforce.seedj.infra.jdbc.iam;

import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

/**
 * Shared PostgreSQL fixture for the IAM repository contract suites shipped in
 * core's test-jar. The suites expect an empty store per test, so every test
 * truncates the IAM tables first; relational-only anchors (rows the schema
 * references but the contract methods never create) are seeded explicitly.
 */
final class JdbcIamContractFixture {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("iam").withUsername("iam").withPassword("iam");

    private static JdbcTemplate jdbc;

    private JdbcIamContractFixture() {
    }

    static synchronized JdbcTemplate jdbc() {
        if (jdbc == null) {
            POSTGRES.start();
            DataSource dataSource = dataSource();
            JdbcMigrationRunner runner = new JdbcMigrationRunner(dataSource);
            new IamSchemaCapability(runner).initialize();
            new OAuth2SchemaCapability(runner).initialize();
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    static void reset() {
        jdbc().execute("""
                TRUNCATE TABLE iam_user, iam_group, iam_role, iam_permission,
                    iam_role_permission, iam_user_role, iam_group_role, iam_group_member,
                    iam_user_credential, iam_totp, iam_recovery_code, iam_mfa_challenge,
                    iam_policy, iam_authz_epoch
                CASCADE
                """);
    }

    static void seedUser(String id) {
        jdbc().update("""
                INSERT INTO iam_user (id, tenant_id, user_name, user_name_normalized, active)
                VALUES (?, 'default', ?, ?, TRUE)
                """, id, id, id);
    }

    static void seedGroup(String id) {
        jdbc().update("""
                INSERT INTO iam_group (id, tenant_id, display_name)
                VALUES (?, 'default', ?)
                """, id, id);
    }

    static void seedRole(String id) {
        jdbc().update("""
                INSERT INTO iam_role (id, tenant_id, code, name, description, parent_id, built_in,
                    version)
                VALUES (?, 'seed', ?, ?, '', NULL, FALSE, 0)
                """, id, id, id);
    }

    private static DataSource dataSource() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUsername(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }
}
