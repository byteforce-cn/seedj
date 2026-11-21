package cn.byteforce.seedj.infra.jdbc.store;

import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.cqrs.spi.CommandStore;
import cn.byteforce.seedj.domain.ObjectSchemaRegistry;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.infra.jdbc.cqrs.CommandSchemaCapability;
import cn.byteforce.seedj.infra.jdbc.cqrs.EventSchemaCapability;
import cn.byteforce.seedj.infra.jdbc.cqrs.PostgresCommandStore;
import cn.byteforce.seedj.infra.jdbc.domain.PostgresTemporalStore;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalSchemaCapability;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalTable;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalTables;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL 的命令/对象存储与迁移：provider 选择归适配器所有。
 *
 * <p>starter 不再在编译期依赖本模块：能力装配只暴露 SPI bean，这里用 {@code seedj.command.provider} / {@code
 * seedj.object.provider} 与 {@link JdbcTemplate} 的 存在性竞争。{@code matchIfMissing=true} 保持原有“有 JDBC 就默认
 * PostgreSQL”的行为； 显式 {@code provider=memory} 时本配置整体退出，由内存适配器接管。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration(
        afterName = {"cn.byteforce.seedj.starter.cqrs.wiring.TemporalCommandAutoConfiguration"},
        after = {DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@ConditionalOnBean(JdbcTemplate.class)
public class PostgresStoreAutoConfiguration {

    /**
     * 命令记录存储。
     *
     * @param jdbc JDBC 模板
     * @return 命令存储
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "seedj.command",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    @ConditionalOnProperty(
            prefix = "seedj.command",
            name = "provider",
            havingValue = "postgres",
            matchIfMissing = true)
    public CommandStore commandStore(JdbcTemplate jdbc) {
        return new PostgresCommandStore(jdbc);
    }

    /**
     * 对象表映射：优先显式声明，否则由 schema 派生。
     *
     * @param tables 显式表定义
     * @param schemas 业务 schema 注册表
     * @return 表注册表
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ObjectSchemaRegistry.class)
    @ConditionalOnProperty(
            prefix = "seedj.object",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public TemporalTables temporalTables(
            ObjectProvider<TemporalTable> tables, ObjectSchemaRegistry schemas) {
        List<TemporalTable> declared = tables.orderedStream().toList();
        if (!declared.isEmpty()) {
            return new TemporalTables(declared);
        }
        return new TemporalTables(schemas.all().stream().map(TemporalTable::from).toList());
    }

    /**
     * 双时间轴对象存储。
     *
     * @param jdbc JDBC 模板
     * @param clock 取时入口
     * @param tables 对象表注册表
     * @param schemas 对象 schema 注册表
     * @return 对象存储
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({TemporalTables.class, ObjectSchemaRegistry.class})
    @ConditionalOnProperty(
            prefix = "seedj.object",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    @ConditionalOnProperty(
            prefix = "seedj.object",
            name = "provider",
            havingValue = "postgres",
            matchIfMissing = true)
    public TemporalStore temporalStore(JdbcTemplate jdbc, Clock clock, TemporalTables tables) {
        return new PostgresTemporalStore(jdbc, clock, tables);
    }

    /**
     * 自定义查询执行器。
     *
     * @param jdbc JDBC 模板
     * @return 执行器
     */
    @Bean
    @ConditionalOnMissingBean
    public cn.byteforce.seedj.read.QueryExecutor queryExecutor(JdbcTemplate jdbc) {
        return new cn.byteforce.seedj.infra.jdbc.read.PostgresQueryExecutor(jdbc);
    }

    /** 命令表迁移。 */
    @Bean
    @ConditionalOnMissingBean(name = "postgresCommandSchemaCapability")
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnProperty(
            prefix = "seedj.command",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    @ConditionalOnProperty(
            prefix = "seedj.command",
            name = "provider",
            havingValue = "postgres",
            matchIfMissing = true)
    public InfrastructureCapability postgresCommandSchemaCapability(
            DataSource dataSource, ObjectProvider<JdbcMigrationRunner> runners) {
        return new CommandSchemaCapability(
                runners.getIfAvailable(() -> new JdbcMigrationRunner(dataSource)));
    }

    /** 双时间轴共享对象迁移。 */
    @Bean
    @ConditionalOnMissingBean(name = "postgresTemporalSchemaCapability")
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnProperty(
            prefix = "seedj.object",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    @ConditionalOnProperty(
            prefix = "seedj.object",
            name = "provider",
            havingValue = "postgres",
            matchIfMissing = true)
    public InfrastructureCapability postgresTemporalSchemaCapability(
            DataSource dataSource, ObjectProvider<JdbcMigrationRunner> runners) {
        return new TemporalSchemaCapability(
                runners.getIfAvailable(() -> new JdbcMigrationRunner(dataSource)));
    }

    /** 事件存储共享迁移。 */
    @Bean
    @ConditionalOnMissingBean(name = "postgresEventSchemaCapability")
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnProperty(
            prefix = "seedj.event-store",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    @ConditionalOnProperty(
            prefix = "seedj.event-store",
            name = "provider",
            havingValue = "postgres",
            matchIfMissing = true)
    public InfrastructureCapability postgresEventSchemaCapability(
            DataSource dataSource, ObjectProvider<JdbcMigrationRunner> runners) {
        return new EventSchemaCapability(
                runners.getIfAvailable(() -> new JdbcMigrationRunner(dataSource)));
    }
}
