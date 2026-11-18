package cn.byteforce.seedj.infra.jdbc.ai;

import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.core.Migration;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import java.util.List;

/**
 * 保证决策账本的链头表、记录表与只追加触发器一定存在。
 *
 * <p>由 {@code InfrastructureInitializer} 在启动期调用；required=true，迁移失败阻断应用启动——账本不可用时 决策面必须
 * fail-closed，而不是等到第一条决策才报错。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class AiSchemaCapability implements InfrastructureCapability {

    public static final String NAME = "postgres-ai-ledger";

    public static final String MIGRATION_SCOPE = "ai";

    private final JdbcMigrationRunner runner;

    private final List<Migration> migrations;

    /**
     * 绑定迁移执行器。
     *
     * @param runner 迁移执行器
     */
    public AiSchemaCapability(JdbcMigrationRunner runner) {
        this.runner = runner;
        this.migrations = AiMigrations.all();
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
