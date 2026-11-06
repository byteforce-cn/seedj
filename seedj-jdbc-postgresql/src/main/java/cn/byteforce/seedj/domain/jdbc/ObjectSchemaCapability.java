package cn.byteforce.seedj.domain.jdbc;

import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.core.Migration;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.jdbc.JdbcMigrationRunner;

import java.util.List;
import java.util.Objects;

/**
 * 把一份 {@link ObjectSchema} 的显式迁移接入启动期能力链。
 *
 * <p>业务表迁移只在部署时执行（V1 create / V2 add column），不在运行时自动改表；迁移 scope
 * 使用能力名，便于按对象类型查看 {@code seedj_schema_history}。顺序 200 保证在共享时点
 * 基础设施（order 120/150）之后。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class ObjectSchemaCapability implements InfrastructureCapability {

    private final String name;

    private final JdbcMigrationRunner runner;

    private final ObjectSchema schema;

    private final List<Migration> migrations;

    /**
     * 构造能力。
     *
     * @param name       能力名（同时作为迁移 scope）
     * @param runner     迁移执行器
     * @param schema     对象 schema（用于一致性校验与诊断）
     * @param migrations 显式迁移
     */
    public ObjectSchemaCapability(String name, JdbcMigrationRunner runner, ObjectSchema schema,
                                  List<Migration> migrations) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("capability name is required");
        }
        this.name = name;
        this.runner = Objects.requireNonNull(runner, "runner");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.migrations = List.copyOf(Objects.requireNonNull(migrations, "migrations"));
    }

    /**
     * 对象 schema。
     *
     * @return schema
     */
    public ObjectSchema schema() {
        return schema;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isReady() {
        return runner.isUpToDate(name, migrations);
    }

    @Override
    public void initialize() {
        runner.migrate(name, migrations);
    }

    @Override
    public void validate() {
        runner.validate(name, migrations);
    }

    @Override
    public int getOrder() {
        return 200;
    }
}
