package cn.byteforce.seedj.infra.jdbc;

import cn.byteforce.seedj.core.InfrastructureException;
import cn.byteforce.seedj.core.Migration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 迁移/回滚演练：结构、历史、事务与重新应用。
 */
@Testcontainers
class JdbcMigrationRollbackTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj_migration_rollback")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private JdbcTemplate jdbc;
    private JdbcMigrationRunner runner;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS seedj_schema_history, mig_rb_a, mig_rb_b");
        runner = new JdbcMigrationRunner(dataSource);
    }

    @Test
    @DisplayName("回滚删除对象并清理历史；重新 migrate 可再次应用")
    void rollbackDropsObjectsAndRemovesHistory() {
        List<Migration> migrations = migrations();

        runner.migrate("rollback", migrations);
        runner.rollback("rollback", migrations, 1);

        assertThat(tableExists("mig_rb_b")).isFalse();
        assertThat(tableExists("mig_rb_a")).isTrue();
        assertThat(historyCount()).isEqualTo(1);

        runner.migrate("rollback", migrations);
        assertThat(tableExists("mig_rb_b")).isTrue();
        assertThat(historyCount()).isEqualTo(2);

        runner.rollback("rollback", migrations, 0);
        assertThat(tableExists("mig_rb_a")).isFalse();
        assertThat(historyCount()).isZero();
    }

    @Test
    @DisplayName("回滚脚本失败时整体事务回滚，表与历史保持不变")
    void rollbackIsTransactionalOnScriptFailure() {
        List<Migration> migrations = List.of(
                new Migration(1, "create a", "CREATE TABLE mig_rb_a (id INT);",
                        "DROP TABLE mig_rb_a;"),
                new Migration(2, "create b", "CREATE TABLE mig_rb_b (id INT);",
                        "DROP TABLE mig_rb_b; SELECT * FROM mig_missing;"));
        runner.migrate("rollback-bad", migrations);

        assertThatThrownBy(() -> runner.rollback("rollback-bad", migrations, 0))
                .isInstanceOf(InfrastructureException.class);

        assertThat(tableExists("mig_rb_a")).isTrue();
        assertThat(tableExists("mig_rb_b")).isTrue();
        assertThat(historyCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("缺少回滚脚本时显式失败，不静默跳过")
    void rollbackWithoutScriptFailsExplicitly() {
        List<Migration> migrations = List.of(
                new Migration(1, "create a", "CREATE TABLE mig_rb_a (id INT);"));
        runner.migrate("rollback-missing", migrations);

        assertThatThrownBy(() -> runner.rollback("rollback-missing", migrations, 0))
                .isInstanceOf(InfrastructureException.class)
                .hasMessageContaining("rollback");

        assertThat(tableExists("mig_rb_a")).isTrue();
        assertThat(historyCount()).isEqualTo(1);
    }

    private static List<Migration> migrations() {
        return List.of(
                new Migration(1, "create a", "CREATE TABLE mig_rb_a (id INT);",
                        "DROP TABLE mig_rb_a;"),
                new Migration(2, "create b", "CREATE TABLE mig_rb_b (id INT);",
                        "DROP TABLE mig_rb_b;"));
    }

    private boolean tableExists(String table) {
        return jdbc.queryForObject("SELECT to_regclass(?)", String.class, table) != null;
    }

    private int historyCount() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM seedj_schema_history", Integer.class);
        return count == null ? 0 : count;
    }
}
