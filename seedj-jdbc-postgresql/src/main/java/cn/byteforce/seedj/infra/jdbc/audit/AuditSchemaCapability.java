package cn.byteforce.seedj.infra.jdbc.audit;

import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.core.Migration;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;

import java.util.List;

/**
 * 保证 audit_entry / audit_head / 迁移历史表一定存在。
 *
 * <p>由 {@code InfrastructureInitializer} 在启动期调用；required=true，迁移失败
 * 阻断应用启动，而不是等到第一条命令才报错。</p>
 */
public class AuditSchemaCapability implements InfrastructureCapability {

    public static final String NAME = "postgres-audit";

    public static final String MIGRATION_SCOPE = "audit";

    private final JdbcMigrationRunner runner;

    private final List<Migration> migrations;

    public AuditSchemaCapability(JdbcMigrationRunner runner) {
        this(runner, AuditMigrations.all());
    }

    AuditSchemaCapability(JdbcMigrationRunner runner, List<Migration> migrations) {
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
        return 110;
    }
}
