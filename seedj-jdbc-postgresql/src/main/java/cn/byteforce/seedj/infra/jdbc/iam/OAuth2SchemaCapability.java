package cn.byteforce.seedj.infra.jdbc.iam;

import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.core.Migration;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import java.util.List;

/** Ensures the SAS JDBC tables exist; separate scope from IAM tables. */
public class OAuth2SchemaCapability implements InfrastructureCapability {

    public static final String NAME = "postgres-oauth2-schema";
    public static final String MIGRATION_SCOPE = "oauth2";

    private final JdbcMigrationRunner runner;
    private final List<Migration> migrations;

    public OAuth2SchemaCapability(JdbcMigrationRunner runner) {
        this(runner, OAuth2Migrations.all());
    }

    OAuth2SchemaCapability(JdbcMigrationRunner runner, List<Migration> migrations) {
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
        return 210;
    }
}
