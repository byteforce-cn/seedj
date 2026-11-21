package cn.byteforce.seedj.infra.jdbc.workflow;

import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.infra.jdbc.JdbcSchemaCapability;

/**
 * WorkflowMigrations 的 schema 能力：迁移数据在 {@link WorkflowMigrations}，模板逻辑在 {@link
 * JdbcSchemaCapability}。
 *
 * <p>本类只保留公开常量与构造器，供既有装配与测试继续引用。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class WorkflowSchemaCapability extends JdbcSchemaCapability {

    /**
     * 构造能力。
     *
     * @param runner 迁移执行器
     */
    public WorkflowSchemaCapability(JdbcMigrationRunner runner) {
        super(WorkflowMigrations.migrationSet(), runner);
    }
}
