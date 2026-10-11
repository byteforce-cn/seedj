package cn.byteforce.seedj.starter.eis.it;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 集成测试基类：容器单例 + 建库脚本（幂等一次）+ 每用例清库。
 *
 * <p>建库脚本 = 模块自带运行时物化（{@code META-INF/eis/ddl/eis-core.ddl}、
 * {@code eis-audit.ddl}） + 测试对象表（{@code eis-it/it-schema.ddl}）。
 * 每用例前重置 schema 并重建，保证用例间零耦合。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public abstract class AbstractEisIntegrationTest {

    private static final Object SCHEMA_LOCK = new Object();

    private static boolean schemaReady;

    private static DataSource dataSource;

    /**
     * 获取共享数据源（DriverManager 实现——测试无需连接池）。
     *
     * @return 数据源
     */
    protected static DataSource dataSource() {
        ensureSchema();
        return dataSource;
    }

    /**
     * 获取 JdbcTemplate。
     *
     * @return JDBC 模板
     */
    protected static JdbcTemplate jdbcTemplate() {
        return new JdbcTemplate(dataSource());
    }

    /**
     * 获取事务管理器（每测试单独构造，无状态）。
     *
     * @return 事务管理器
     */
    protected static PlatformTransactionManager transactionManager() {
        return new DataSourceTransactionManager(dataSource());
    }

    /**
     * 获取事务模板。
     *
     * @return 事务模板
     */
    protected static TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager());
    }

    private static void ensureSchema() {
        synchronized (SCHEMA_LOCK) {
            if (schemaReady) {
                return;
            }
            EisPostgresContainer container = EisPostgresContainer.getInstance();
            DriverManagerDataSource source = new DriverManagerDataSource(container.getJdbcUrl(),
                    container.getUsername(), container.getPassword());
            dataSource = source;
            try (Connection connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("META-INF/eis/ddl/eis-core.ddl"));
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("META-INF/eis/ddl/eis-audit.ddl"));
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("eis-it/it-schema.ddl"));
            } catch (Exception ex) {
                throw new IllegalStateException("集成测试建库失败", ex);
            }
            schemaReady = true;
        }
    }

    /**
     * 每用例前清库（继承式钩子）。
     */
    @BeforeEach
    void resetDatabase() {
        resetData();
    }

    /**
     * 重置业务数据。
     */
    protected static void resetData() {
        JdbcTemplate jdbc = jdbcTemplate();
        jdbc.execute("TRUNCATE command, event, view, eis_idempotency, eis_command_event,"
                + " eis_command_rejection, eis_outbox, audit_entry, it_widget");
        jdbc.execute("UPDATE eis_audit_head SET last_sequence = 0, last_hash = repeat('0', 64)");
    }
}
