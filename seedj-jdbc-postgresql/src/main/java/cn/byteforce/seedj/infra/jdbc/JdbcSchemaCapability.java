package cn.byteforce.seedj.infra.jdbc;

import cn.byteforce.seedj.core.InfrastructureCapability;
import java.util.Objects;

/**
 * 通用 schema 能力：把一份 {@link MigrationSet} 接入启动期能力链。
 *
 * <p>各能力不再复制 {@code isReady/initialize/validate} 模板，只声明迁移数据。能力名与迁移 scope 分离：能力名面向 {@code
 * InfrastructureInitializer} 诊断，scope 面向 {@code seedj_schema_history} 分组。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class JdbcSchemaCapability implements InfrastructureCapability {

    private final MigrationSet set;

    private final JdbcMigrationRunner runner;

    /**
     * 构造能力。
     *
     * @param set 迁移声明
     * @param runner 迁移执行器
     */
    public JdbcSchemaCapability(MigrationSet set, JdbcMigrationRunner runner) {
        this.set = Objects.requireNonNull(set, "set");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /**
     * 迁移声明。
     *
     * @return 声明
     */
    public MigrationSet migrationSet() {
        return set;
    }

    @Override
    public String getName() {
        return set.name();
    }

    @Override
    public boolean isReady() {
        return runner.isUpToDate(set.scope(), set.migrations());
    }

    @Override
    public void initialize() {
        runner.migrate(set.scope(), set.migrations());
    }

    @Override
    public void validate() {
        runner.validate(set.scope(), set.migrations());
    }

    @Override
    public int getOrder() {
        return set.order();
    }
}
