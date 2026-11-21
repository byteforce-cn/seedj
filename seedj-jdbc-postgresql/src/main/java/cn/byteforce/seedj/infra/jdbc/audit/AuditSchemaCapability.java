package cn.byteforce.seedj.infra.jdbc.audit;

import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.infra.jdbc.JdbcSchemaCapability;

/**
 * AuditMigrations 的 schema 能力：迁移数据在 {@link AuditMigrations}，模板逻辑在 {@link JdbcSchemaCapability}。
 *
 * <p>本类只保留公开常量与构造器，供既有装配与测试继续引用。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class AuditSchemaCapability extends JdbcSchemaCapability {

    /** 能力名称。 */
    public static final String NAME = "postgres-audit";

    /** 迁移 scope。 */
    public static final String MIGRATION_SCOPE = "audit";

    /**
     * 构造能力。
     *
     * @param runner 迁移执行器
     */
    public AuditSchemaCapability(JdbcMigrationRunner runner) {
        super(AuditMigrations.migrationSet(), runner);
    }
}
