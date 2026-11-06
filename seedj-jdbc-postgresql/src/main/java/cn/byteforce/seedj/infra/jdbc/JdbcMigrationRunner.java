package cn.byteforce.seedj.infra.jdbc;

import cn.byteforce.seedj.core.InfrastructureErrorCode;
import cn.byteforce.seedj.core.InfrastructureException;
import cn.byteforce.seedj.core.Migration;
import cn.byteforce.seedj.core.MigrationChecksums;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptException;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小可用的版本化迁移执行器（不引入 Flyway）。
 *
 * <p>语义与 Flyway 对齐：
 * <ul>
 *   <li>迁移按 {@code version} 升序执行，已执行的版本记录在 {@code seedj_schema_history}；</li>
 *   <li>已执行迁移的 checksum 与当前代码不一致时拒绝启动；</li>
 *   <li>数据库中存在本地代码不认识的版本时拒绝启动；</li>
 *   <li>整个迁移过程在单个事务内完成，并用 PostgreSQL advisory lock 串行化多实例启动；</li>
 *   <li>重复执行是幂等的：已应用的版本不会再次执行。</li>
 * </ul>
 *
 * <p>只支持 PostgreSQL：advisory lock 与 DDL 事务语义都是方言相关的。
 */
public class JdbcMigrationRunner {

    private static final long ADVISORY_LOCK_KEY = 8_844_220_013_37L;

    private static final String CREATE_HISTORY = """
            CREATE TABLE IF NOT EXISTS seedj_schema_history (
                scope        VARCHAR(128) NOT NULL,
                version      INT          NOT NULL,
                description  VARCHAR(255) NOT NULL,
                checksum     CHAR(64)     NOT NULL,
                applied_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
                execution_ms INT          NOT NULL,
                PRIMARY KEY (scope, version)
            )
            """;

    private final DataSource dataSource;
    private final String scope;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public JdbcMigrationRunner(DataSource dataSource) {
        this(dataSource, "default");
    }

    /**
     * @deprecated scope belongs to the migration call, not to the runner, so one
     * runner bean can serve every capability. Use {@link #migrate(String, List)}.
     */
    @Deprecated(forRemoval = false)
    public JdbcMigrationRunner(DataSource dataSource, String scope) {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("migration scope must not be blank");
        }
        this.dataSource = dataSource;
        this.scope = scope;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
    }

    /**
     * 执行所有尚未应用的迁移。
     */
    public void migrate(List<Migration> migrations) {
        migrate(scope, migrations);
    }

    /**
     * Executes all pending migrations for one capability scope.
     */
    public void migrate(String scope, List<Migration> migrations) {
        String migrationScope = requireScope(scope);
        List<Migration> ordered = ordered(migrations);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                // 事务级 advisory lock：同一时刻只有一个实例能执行迁移。
                jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + ADVISORY_LOCK_KEY + ")");
                jdbcTemplate.execute(CREATE_HISTORY);

                Map<Integer, String> applied = appliedChecksums(migrationScope);
                verifyApplied(ordered, applied);

                for (Migration migration : ordered) {
                    if (applied.containsKey(migration.version())) {
                        continue;
                    }
                    long startedAt = System.nanoTime();
                    executeScript(migration);
                    int elapsedMs = (int) ((System.nanoTime() - startedAt) / 1_000_000);
                    jdbcTemplate.update("""
                            INSERT INTO seedj_schema_history
                                (scope, version, description, checksum, execution_ms)
                            VALUES (?, ?, ?, ?, ?)
                            """,
                            migrationScope,
                            migration.version(),
                            migration.description(),
                            MigrationChecksums.checksum(migration),
                            elapsedMs);
                }
            });
        } catch (InfrastructureException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InfrastructureException(InfrastructureErrorCode.MIGRATION_FAILED,
                    "database migration failed: " + e.getMessage(), e);
        }
    }

    /**
     * 校验数据库中的迁移历史与当前代码一致。
     *
     * @throws InfrastructureException 历史表缺失、存在未应用迁移、checksum 不一致或存在未知版本
     */
    public void validate(List<Migration> migrations) {
        validate(scope, migrations);
    }

    /**
     * Validates the history of one capability scope.
     */
    public void validate(String scope, List<Migration> migrations) {
        String migrationScope = requireScope(scope);
        List<Migration> ordered = ordered(migrations);
        Map<Integer, String> applied;
        try {
            applied = appliedChecksums(migrationScope);
        } catch (DataAccessException e) {
            throw new InfrastructureException(InfrastructureErrorCode.MIGRATION_PENDING,
                    "schema history is missing; pending migrations: " + ordered.size(), e);
        }
        verifyApplied(ordered, applied);
        for (Migration migration : ordered) {
            if (!applied.containsKey(migration.version())) {
                throw new InfrastructureException(InfrastructureErrorCode.MIGRATION_PENDING,
                        "pending migration: V" + migration.version() + " " + migration.description());
            }
        }
        if (applied.size() != ordered.size()) {
            throw new InfrastructureException(InfrastructureErrorCode.MIGRATION_PENDING,
                    "schema history has more entries than local migrations");
        }
    }

    /**
     * 是否所有迁移都已应用且 checksum 一致。历史表缺失返回 {@code false}。
     */
    public boolean isUpToDate(List<Migration> migrations) {
        return isUpToDate(scope, migrations);
    }

    /**
     * Whether one capability scope is fully applied with matching checksums.
     */
    public boolean isUpToDate(String scope, List<Migration> migrations) {
        String migrationScope = requireScope(scope);
        List<Migration> ordered = ordered(migrations);
        try {
            Map<Integer, String> applied = appliedChecksums(migrationScope);
            if (applied.size() != ordered.size()) {
                return false;
            }
            for (Migration migration : ordered) {
                String checksum = applied.get(migration.version());
                if (checksum == null
                        || !checksum.equals(MigrationChecksums.checksum(migration))) {
                    return false;
                }
            }
            return true;
        } catch (DataAccessException e) {
            return false;
        }
    }

    private void verifyApplied(List<Migration> ordered, Map<Integer, String> applied) {
        Map<Integer, Migration> local = new LinkedHashMap<>();
        for (Migration migration : ordered) {
            local.put(migration.version(), migration);
        }
        for (Map.Entry<Integer, String> entry : applied.entrySet()) {
            Migration migration = local.get(entry.getKey());
            if (migration == null) {
                throw new InfrastructureException(InfrastructureErrorCode.MIGRATION_UNKNOWN_VERSION,
                        "database has migration V" + entry.getKey()
                                + " which is not present in this application");
            }
            String expected = MigrationChecksums.checksum(migration);
            if (!expected.equals(entry.getValue())) {
                throw new InfrastructureException(
                        InfrastructureErrorCode.MIGRATION_CHECKSUM_MISMATCH,
                        "migration V" + migration.version() + " checksum mismatch: database="
                                + entry.getValue() + ", application=" + expected);
            }
        }
    }

    private Map<Integer, String> appliedChecksums(String scope) {
        Map<Integer, String> applied = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT version, checksum FROM seedj_schema_history
                WHERE scope = ? ORDER BY version
                """, rs -> {
            applied.put(rs.getInt("version"), rs.getString("checksum"));
        }, scope);
        return applied;
    }

    private static String requireScope(String scope) {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("migration scope must not be blank");
        }
        return scope;
    }

    /**
     * 回滚到指定版本（不含）：逆序执行 {@code version > targetVersion} 的回滚脚本，
     * 并删除对应历史行。整个过程在同一事务与 advisory lock 内完成。
     *
     * @param scope         迁移 scope
     * @param migrations    本地迁移列表
     * @param targetVersion 目标版本；{@code 0} 表示全部回滚
     */
    public void rollback(String scope, List<Migration> migrations, int targetVersion) {
        if (targetVersion < 0) {
            throw new IllegalArgumentException("targetVersion must be >= 0");
        }
        String migrationScope = requireScope(scope);
        List<Migration> ordered = ordered(migrations);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + ADVISORY_LOCK_KEY + ")");
                jdbcTemplate.execute(CREATE_HISTORY);
                Map<Integer, String> applied = appliedChecksums(migrationScope);
                verifyApplied(ordered, applied);
                for (int i = ordered.size() - 1; i >= 0; i--) {
                    Migration migration = ordered.get(i);
                    if (migration.version() <= targetVersion
                            || !applied.containsKey(migration.version())) {
                        continue;
                    }
                    if (migration.rollback() == null) {
                        throw new InfrastructureException(InfrastructureErrorCode.MIGRATION_FAILED,
                                "migration V" + migration.version()
                                        + " has no rollback script");
                    }
                    executeScript(migration, migration.rollback(), "rollback");
                    jdbcTemplate.update("""
                            DELETE FROM seedj_schema_history WHERE scope = ? AND version = ?
                            """, migrationScope, migration.version());
                }
            });
        } catch (InfrastructureException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InfrastructureException(InfrastructureErrorCode.MIGRATION_FAILED,
                    "database rollback failed: " + e.getMessage(), e);
        }
    }

    private void executeScript(Migration migration) {
        executeScript(migration, migration.content(), "migration");
    }

    private void executeScript(Migration migration, String script, String kind) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            EncodedResource resource = new EncodedResource(
                    new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8);
            ScriptUtils.executeSqlScript(connection, resource);
        } catch (ScriptException e) {
            throw new InfrastructureException(InfrastructureErrorCode.MIGRATION_FAILED,
                    "migration V" + migration.version() + " (" + migration.description()
                            + ") " + kind + " failed", e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private static List<Migration> ordered(List<Migration> migrations) {
        List<Migration> ordered = new ArrayList<>(migrations);
        ordered.sort((left, right) -> Integer.compare(left.version(), right.version()));
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i).version() == ordered.get(i - 1).version()) {
                throw new IllegalArgumentException(
                        "duplicate migration version: V" + ordered.get(i).version());
            }
        }
        return List.copyOf(ordered);
    }
}
