package cn.byteforce.seedj.infra.jdbc.workflow;

import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.core.Migration;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;

import java.util.List;

/**
 * 工作流表迁移能力：只在显式装配时执行，启动期不按运行期状态生成 DDL。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class WorkflowSchemaCapability implements InfrastructureCapability {

    private final JdbcMigrationRunner runner;

    private final List<Migration> migrations = List.of(
            new Migration(1, "create workflow tables", WorkflowMigrations.v1()));

    /**
     * 构造能力。
     *
     * @param runner 迁移执行器
     */
    public WorkflowSchemaCapability(JdbcMigrationRunner runner) {
        this.runner = runner;
    }

    @Override
    public String getName() {
        return WorkflowMigrations.SCOPE;
    }

    @Override
    public boolean isReady() {
        return runner.isUpToDate(getName(), migrations);
    }

    @Override
    public void initialize() {
        runner.migrate(getName(), migrations);
    }

    @Override
    public void validate() {
        runner.validate(getName(), migrations);
    }

    @Override
    public int getOrder() {
        return 210;
    }
}
