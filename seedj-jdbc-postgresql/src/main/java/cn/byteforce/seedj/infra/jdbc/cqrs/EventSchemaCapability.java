package cn.byteforce.seedj.infra.jdbc.cqrs;

import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.infra.jdbc.JdbcSchemaCapability;

/**
 * EventMigrations 的 schema 能力：迁移数据在 {@link EventMigrations}，模板逻辑在 {@link JdbcSchemaCapability}。
 *
 * <p>本类只保留公开常量与构造器，供既有装配与测试继续引用。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class EventSchemaCapability extends JdbcSchemaCapability {

    /** 能力名称。 */
    public static final String NAME = "postgres-event-store";

    /** 迁移 scope。 */
    public static final String MIGRATION_SCOPE = "event";

    /**
     * 构造能力。
     *
     * @param runner 迁移执行器
     */
    public EventSchemaCapability(JdbcMigrationRunner runner) {
        super(EventMigrations.migrationSet(), runner);
    }
}
