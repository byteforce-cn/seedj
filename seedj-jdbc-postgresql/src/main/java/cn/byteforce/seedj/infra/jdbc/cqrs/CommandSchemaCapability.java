package cn.byteforce.seedj.infra.jdbc.cqrs;

import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.core.Migration;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import java.util.List;

/**
 * 保证 {@code command_record} 表与索引一定存在。
 *
 * <p>由 {@code InfrastructureInitializer} 在启动期调用；迁移失败阻断启动， 而不是等到第一条命令才报错。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CommandSchemaCapability implements InfrastructureCapability {

    public static final String NAME = "postgres-command";

    public static final String MIGRATION_SCOPE = "command";

    private final JdbcMigrationRunner runner;

    private final List<Migration> migrations;

    public CommandSchemaCapability(JdbcMigrationRunner runner) {
        this(runner, CommandMigrations.all());
    }

    CommandSchemaCapability(JdbcMigrationRunner runner, List<Migration> migrations) {
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
        return 130;
    }
}
