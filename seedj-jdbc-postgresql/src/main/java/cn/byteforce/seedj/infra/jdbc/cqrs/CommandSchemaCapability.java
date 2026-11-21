package cn.byteforce.seedj.infra.jdbc.cqrs;

import cn.byteforce.seedj.core.Migration;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.infra.jdbc.JdbcSchemaCapability;
import java.util.List;

/**
 * 命令记录表的 schema 能力：迁移数据在 {@link CommandMigrations}，模板逻辑在 {@link
 * JdbcSchemaCapability}。
 *
 * <p>本类只保留公开常量与构造器；2 参构造器供升级测试替换迁移列表。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CommandSchemaCapability extends JdbcSchemaCapability {

    /** 能力名称。 */
    public static final String NAME = "postgres-command";

    /** 迁移 scope。 */
    public static final String MIGRATION_SCOPE = "command";

    /**
     * 构造能力。
     *
     * @param runner 迁移执行器
     */
    public CommandSchemaCapability(JdbcMigrationRunner runner) {
        super(CommandMigrations.migrationSet(), runner);
    }

    CommandSchemaCapability(JdbcMigrationRunner runner, List<Migration> migrations) {
        super(CommandMigrations.migrationSet().withMigrations(migrations), runner);
    }
}
