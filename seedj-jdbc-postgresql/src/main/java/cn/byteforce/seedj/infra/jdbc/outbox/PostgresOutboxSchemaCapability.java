package cn.byteforce.seedj.infra.jdbc.outbox;

import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.infra.jdbc.JdbcSchemaCapability;

/**
 * PostgresOutboxMigrations 的 schema 能力：迁移数据在 {@link PostgresOutboxMigrations}，模板逻辑在 {@link
 * JdbcSchemaCapability}。
 *
 * <p>本类只保留公开常量与构造器，供既有装配与测试继续引用。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class PostgresOutboxSchemaCapability extends JdbcSchemaCapability {

    /** 能力名称。 */
    public static final String NAME = "postgres-outbox";

    /** 迁移 scope。 */
    public static final String MIGRATION_SCOPE = "outbox";

    /**
     * 构造能力。
     *
     * @param runner 迁移执行器
     */
    public PostgresOutboxSchemaCapability(JdbcMigrationRunner runner) {
        super(PostgresOutboxMigrations.migrationSet(), runner);
    }
}
