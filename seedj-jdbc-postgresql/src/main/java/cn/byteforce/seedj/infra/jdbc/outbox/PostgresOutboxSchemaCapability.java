package cn.byteforce.seedj.infra.jdbc.outbox;

import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.core.Migration;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;

import java.util.List;

/**
 * 保证 outbox / outbox_audit / 迁移历史表一定存在。
 *
 * <p>由 outbox starter 或聚合 starter 的 {@code InfrastructureInitializer}
 * 在启动期调用；required=true，迁移失败会阻断应用启动，而不是等到第一次 append 才报错。
 */
public class PostgresOutboxSchemaCapability implements InfrastructureCapability {

    public static final String NAME = "postgres-outbox";

    /** 迁移历史中的 scope，避免与其他能力的迁移版本互相干扰。 */
    public static final String MIGRATION_SCOPE = "outbox";

    private final JdbcMigrationRunner runner;
    private final List<Migration> migrations;

    public PostgresOutboxSchemaCapability(JdbcMigrationRunner runner) {
        this(runner, PostgresOutboxMigrations.all());
    }

    PostgresOutboxSchemaCapability(JdbcMigrationRunner runner, List<Migration> migrations) {
        this.runner = runner;
        this.migrations = List.copyOf(migrations);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isReady() {
        return runner.isUpToDate(MIGRATION_SCOPE, migrations);
    }

    @Override
    public void initialize() {
        runner.migrate(MIGRATION_SCOPE, migrations);
    }

    @Override
    public void validate() {
        runner.validate(MIGRATION_SCOPE, migrations);
    }

    @Override
    public int getOrder() {
        return 100;
    }
}
