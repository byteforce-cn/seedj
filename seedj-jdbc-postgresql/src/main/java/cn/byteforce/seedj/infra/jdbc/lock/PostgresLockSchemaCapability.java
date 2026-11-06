package cn.byteforce.seedj.infra.jdbc.lock;

import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.core.Migration;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;

import java.util.List;

/**
 * 锁表迁移能力：order 150（共享时点基础设施之后、业务表之前）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class PostgresLockSchemaCapability implements InfrastructureCapability {

    /** 能力名，同时是迁移 scope。 */
    public static final String NAME = "lock";

    private final JdbcMigrationRunner runner;

    private final List<Migration> migrations;

    /**
     * 构造能力。
     *
     * @param runner 迁移执行器
     */
    public PostgresLockSchemaCapability(JdbcMigrationRunner runner) {
        this(runner, PostgresLockMigrations.all());
    }

    PostgresLockSchemaCapability(JdbcMigrationRunner runner, List<Migration> migrations) {
        this.runner = runner;
        this.migrations = List.copyOf(migrations);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isReady() {
        return runner.isUpToDate(NAME, migrations);
    }

    @Override
    public void initialize() {
        runner.migrate(NAME, migrations);
    }

    @Override
    public void validate() {
        runner.validate(NAME, migrations);
    }

    @Override
    public int getOrder() {
        return 150;
    }
}
