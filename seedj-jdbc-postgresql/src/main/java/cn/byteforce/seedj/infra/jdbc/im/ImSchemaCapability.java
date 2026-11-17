package cn.byteforce.seedj.infra.jdbc.im;

import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.core.Migration;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import java.util.List;

/**
 * 保证 IM 的五张表与 {@code im_seq} 在任何 IM 存储 bean 使用前存在。
 *
 * <p>迁移 scope 是 {@code im}，与 IAM（{@code iam}）、outbox（{@code outbox}）等能力互不干扰； 执行顺序排在对象/IAM
 * 之后（220），因为它们不依赖 IM，而 IM 的回滚测试希望自己的表最后建。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class ImSchemaCapability implements InfrastructureCapability {

    /** 能力名称。 */
    public static final String NAME = "postgres-im";

    /** 迁移 scope。 */
    public static final String MIGRATION_SCOPE = "im";

    private final JdbcMigrationRunner runner;

    private final List<Migration> migrations;

    /**
     * 构造能力。
     *
     * @param runner 迁移执行器
     */
    public ImSchemaCapability(JdbcMigrationRunner runner) {
        this(runner, ImMigrations.all());
    }

    ImSchemaCapability(JdbcMigrationRunner runner, List<Migration> migrations) {
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
        return 220;
    }
}
