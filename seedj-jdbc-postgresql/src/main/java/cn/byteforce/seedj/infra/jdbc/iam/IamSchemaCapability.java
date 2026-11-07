package cn.byteforce.seedj.infra.jdbc.iam;

import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.core.Migration;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import java.util.List;

/** Ensures the IAM tables exist before any identity bean is used. */
public class IamSchemaCapability implements InfrastructureCapability {

    public static final String NAME = "postgres-iam-schema";
    public static final String MIGRATION_SCOPE = "iam";

    private final JdbcMigrationRunner runner;
    private final List<Migration> migrations;

    public IamSchemaCapability(JdbcMigrationRunner runner) {
        this(runner, IamMigrations.all());
    }

    IamSchemaCapability(JdbcMigrationRunner runner, List<Migration> migrations) {
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
        return 200;
    }
}
