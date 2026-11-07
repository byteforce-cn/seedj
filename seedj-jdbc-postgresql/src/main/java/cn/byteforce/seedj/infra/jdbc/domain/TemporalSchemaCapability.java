package cn.byteforce.seedj.infra.jdbc.domain;

import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.core.Migration;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import java.util.List;

/**
 * 保证双时间轴共享对象（扩展、域类型、时点函数）一定存在。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class TemporalSchemaCapability implements InfrastructureCapability {

    public static final String NAME = "postgres-temporal";

    public static final String MIGRATION_SCOPE = "temporal";

    private final JdbcMigrationRunner runner;

    private final List<Migration> migrations;

    public TemporalSchemaCapability(JdbcMigrationRunner runner) {
        this(runner, TemporalMigrations.all());
    }

    TemporalSchemaCapability(JdbcMigrationRunner runner, List<Migration> migrations) {
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
        return 120;
    }
}
