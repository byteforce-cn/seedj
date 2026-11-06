package cn.byteforce.seedj.cqrs.wiring;

import cn.byteforce.seedj.biz.autoconfigure.BizEnabledMarker;
import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.clock.Clocks;
import cn.byteforce.seedj.core.security.CallerIdCodec;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.biz.validation.BeanValidationCommandValidator;
import cn.byteforce.seedj.cqrs.CommandCatalog;
import cn.byteforce.seedj.cqrs.CommandPolicy;
import cn.byteforce.seedj.cqrs.CommandValidator;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.cqrs.TemporalCommandAgent;
import cn.byteforce.seedj.cqrs.TemporalCommandAudit;
import cn.byteforce.seedj.cqrs.jdbc.CommandSchemaCapability;
import cn.byteforce.seedj.cqrs.jdbc.PostgresCommandStore;
import cn.byteforce.seedj.cqrs.memory.InMemoryCommandStore;
import cn.byteforce.seedj.cqrs.spi.CommandStore;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.cqrs.web.CommandController;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ObjectSchemaRegistry;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.domain.jdbc.PostgresTemporalStore;
import cn.byteforce.seedj.domain.jdbc.TemporalSchemaCapability;
import cn.byteforce.seedj.domain.jdbc.TemporalTable;
import cn.byteforce.seedj.domain.jdbc.TemporalTables;
import cn.byteforce.seedj.domain.memory.InMemoryTemporalStore;
import cn.byteforce.seedj.domain.web.ObjectQueryController;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.id.Uuid7IdGenerator;
import cn.byteforce.seedj.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.lock.LockProvider;
import cn.byteforce.seedj.outbox.OutboxWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 命令运行时、对象查询与 HTTP 面的默认装配。
 *
 * <p>provider 选择是显式的：{@code seedj.command.provider} 与
 * {@code seedj.object.provider} 缺省时按依赖推断（有 JDBC 模板则 PostgreSQL，
 * 否则内存）；显式指定 PostgreSQL 但缺依赖时启动失败，不静默降级。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration(
        afterName = {"cn.byteforce.seedj.biz.security.BizSecurityAutoConfiguration",
                "cn.byteforce.seedj.audit.wiring.AuditAutoConfiguration"},
        after = {DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@ConditionalOnBean(BizEnabledMarker.class)
@EnableConfigurationProperties(CommandRuntimeProperties.class)
public class TemporalCommandAutoConfiguration {

    private static final String COMMAND_PROVIDER = "seedj.command.provider";

    private static final String OBJECT_PROVIDER = "seedj.object.provider";

    /**
     * 契约面统一时钟；应用可覆盖。
     *
     * @return 系统时钟（UTC 微秒）
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock seedjContractClock() {
        return Clocks.system();
    }

    /**
     * 命令/事件标识生成器；应用可覆盖。
     *
     * @param clock 取时入口
     * @return UUIDv7 生成器
     */
    @Bean(name = "commandIdGenerator")
    @ConditionalOnMissingBean(name = "commandIdGenerator")
    public IdGenerator<UUID> commandIdGenerator(Clock clock) {
        return new Uuid7IdGenerator(clock);
    }

    /**
     * 命令记录存储。
     *
     * @param environment 配置
     * @param jdbc        JDBC 模板
     * @return 命令存储
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "seedj.command", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public CommandStore commandStore(Environment environment,
                                     ObjectProvider<JdbcTemplate> jdbc) {
        JdbcTemplate template = jdbc.getIfAvailable();
        String provider = environment.getProperty(COMMAND_PROVIDER,
                template == null ? "memory" : "postgres");
        return switch (provider) {
            case "memory" -> new InMemoryCommandStore();
            case "postgres" -> {
                if (template == null) {
                    throw new IllegalStateException(
                            "seedj.command.provider=postgres requires a DataSource/JdbcTemplate");
                }
                yield new PostgresCommandStore(template);
            }
            default -> throw new IllegalStateException(
                    "unknown seedj.command.provider: " + provider);
        };
    }

    /**
     * 对象类型到物理表的注册表。
     *
     * @param tables 应用声明的对象表
     * @return 注册表
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "seedj.object", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public ObjectSchemaRegistry objectSchemaRegistry(ObjectProvider<ObjectSchema> schemas) {
        List<ObjectSchema> declared = schemas.orderedStream().toList();
        return declared.isEmpty()
                ? ObjectSchemaRegistry.empty() : new ObjectSchemaRegistry(declared);
    }

    /**
     * 对象表映射：优先使用显式声明的 {@link TemporalTable}，否则由 {@link ObjectSchema}
     * 派生。两者都没有时是空注册表，访问未知类型会在使用点失败。
     *
     * @param tables  显式表定义
     * @param schemas 业务 schema 注册表
     * @return 表注册表
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "seedj.object", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public TemporalTables temporalTables(ObjectProvider<TemporalTable> tables,
                                         ObjectSchemaRegistry schemas) {
        List<TemporalTable> declared = tables.orderedStream().toList();
        if (!declared.isEmpty()) {
            return new TemporalTables(declared);
        }
        return new TemporalTables(schemas.all().stream().map(TemporalTable::from).toList());
    }

    /**
     * 双时间轴对象存储。
     *
     * @param environment 配置
     * @param jdbc        JDBC 模板
     * @param tables      对象表注册表
     * @param schemas     对象 schema 注册表
     * @param clock       取时入口
     * @return 对象存储
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "seedj.object", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public TemporalStore temporalStore(Environment environment,
                                       ObjectProvider<JdbcTemplate> jdbc,
                                       ObjectProvider<TemporalTables> tables,
                                       ObjectSchemaRegistry schemas,
                                       Clock clock) {
        JdbcTemplate template = jdbc.getIfAvailable();
        TemporalTables registry = tables.getIfAvailable();
        String provider = environment.getProperty(OBJECT_PROVIDER,
                template == null || registry == null ? "memory" : "postgres");
        return switch (provider) {
            case "memory" -> new InMemoryTemporalStore(clock, schemas);
            case "postgres" -> {
                if (template == null) {
                    throw new IllegalStateException(
                            "seedj.object.provider=postgres requires a DataSource/JdbcTemplate");
                }
                if (registry == null || registry.isEmpty()) {
                    throw new IllegalStateException(
                            "seedj.object.provider=postgres requires registered TemporalTables "
                                    + "or ObjectSchema beans");
                }
                yield new PostgresTemporalStore(template, clock, registry);
            }
            default -> throw new IllegalStateException(
                    "unknown seedj.object.provider: " + provider);
        };
    }

    /**
     * 命令运行时。
     *
     * @param commands  命令存储
     * @param objects   对象存储
     * @param tx        事务边界
     * @param locks     锁
     * @param outbox    outbox
     * @param clock     取时入口
     * @param ids       标识生成器
     * @param audit     临时命令审计 seam
     * @return 运行时
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "seedj.command", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public CommandRuntime commandRuntime(CommandStore commands, TemporalStore objects,
                                         ObjectProvider<TransactionRunner> tx,
                                         ObjectProvider<LockProvider> locks,
                                         ObjectProvider<OutboxWriter> outbox,
                                         Clock clock,
                                         @Qualifier("commandIdGenerator")
                                         IdGenerator<UUID> ids,
                                         ObjectProvider<TemporalCommandAudit> audit,
                                         CommandRuntimeProperties properties) {
        TransactionRunner transactionRunner = tx.getIfAvailable();
        LockProvider lockProvider = locks.getIfAvailable();
        OutboxWriter outboxWriter = outbox.getIfAvailable();

        boolean degrade = properties.isDegradedMode();
        if (!degrade) {
            requireDependency(transactionRunner, "TransactionRunner");
            requireDependency(lockProvider, "LockProvider");
            if (properties.isRequireOutbox()) {
                requireDependency(outboxWriter, "OutboxWriter");
            }
        }
        StringBuilder banner = new StringBuilder();
        if (transactionRunner == null) {
            transactionRunner = TransactionRunner.direct();
            banner.append(" tx=direct");
        }
        if (lockProvider == null) {
            lockProvider = LockProvider.disabled();
            banner.append(" lock=disabled");
        }
        if (outboxWriter == null) {
            outboxWriter = OutboxWriter.discard();
            banner.append(" outbox=discard");
        }
        if (!banner.isEmpty()) {
            org.slf4j.LoggerFactory.getLogger(TemporalCommandAutoConfiguration.class)
                    .warn("seedj: command runtime running in degraded mode:{}", banner);
        }
        return new CommandRuntime(new CommandRuntime.Options(
                transactionRunner, lockProvider, Duration.ofSeconds(30), clock, ids, commands,
                objects, outboxWriter, audit.getIfAvailable(TemporalCommandAudit::noop)));
    }

    private static void requireDependency(Object dependency, String name) {
        if (dependency == null) {
            throw new IllegalStateException("seedj.command requires a " + name
                    + "; configure it, or set seedj.command.degraded-mode=true only for"
                    + " local/test");
        }
    }

    /**
     * 命令注册表。
     *
     * @param agents 应用声明的命令代理
     * @return 注册表
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "seedj.command", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public CommandCatalog commandCatalog(List<TemporalCommandAgent<?>> agents,
                                         CommandPolicy policy) {
        return new CommandCatalog(agents, policy);
    }

    /**
     * 命令级授权策略：以 descriptor 的 permission 为准。
     *
     * @return 权限策略
     */
    @Bean
    @ConditionalOnMissingBean
    public CommandPolicy commandPolicy() {
        return CommandPolicy.permissions();
    }

    /**
     * 调用方标识编解码：默认要求 UUID；非 UUID IdP 由应用覆盖为 nameUuid()。
     *
     * @return 编解码
     */
    @Bean
    @ConditionalOnMissingBean
    public CallerIdCodec callerIdCodec() {
        return CallerIdCodec.uuid();
    }

    /**
     * 命令载荷校验：存在 Bean Validation 时使用注解校验，否则不校验。
     *
     * @param validator Bean Validation 校验器
     * @return 命令校验器
     */
    @Bean
    @ConditionalOnMissingBean(CommandValidator.class)
    @ConditionalOnClass(name = "jakarta.validation.Validator")
    public CommandValidator beanValidationCommandValidator(
            ObjectProvider<jakarta.validation.Validator> validator) {
        jakarta.validation.Validator delegate = validator.getIfAvailable();
        return delegate == null
                ? CommandValidator.none() : new BeanValidationCommandValidator(delegate);
    }

    /**
     * PostgreSQL 命令表迁移。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({JdbcTemplate.class, DataSource.class})
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnProperty(prefix = "seedj.command", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    static class CommandSchemaConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "postgresCommandSchemaCapability")
        @ConditionalOnProperty(prefix = "seedj.command", name = "provider",
                havingValue = "postgres", matchIfMissing = true)
        InfrastructureCapability postgresCommandSchemaCapability(
                DataSource dataSource, ObjectProvider<JdbcMigrationRunner> runners) {
            return new CommandSchemaCapability(
                    runners.getIfAvailable(() -> new JdbcMigrationRunner(dataSource)));
        }
    }

    /**
     * PostgreSQL 双时间轴共享对象迁移。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({JdbcTemplate.class, DataSource.class})
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnProperty(prefix = "seedj.object", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    static class TemporalSchemaConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "postgresTemporalSchemaCapability")
        @ConditionalOnProperty(prefix = "seedj.object", name = "provider",
                havingValue = "postgres", matchIfMissing = true)
        InfrastructureCapability postgresTemporalSchemaCapability(
                DataSource dataSource, ObjectProvider<JdbcMigrationRunner> runners) {
            return new TemporalSchemaCapability(
                    runners.getIfAvailable(() -> new JdbcMigrationRunner(dataSource)));
        }
    }

    /**
     * Web 面：命令与对象查询控制器。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class WebConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "seedj.command", name = "enabled", havingValue = "true",
                matchIfMissing = true)
        CommandController commandController(CommandCatalog catalog, CommandStore commands,
                                            ObjectProvider<CallerProvider> callers,
                                            CallerIdCodec callerIds) {
            CallerProvider provider = callers.getIfAvailable();
            return new CommandController(catalog, commands,
                    provider == null ? Optional::empty : provider, callerIds);
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "seedj.object", name = "enabled", havingValue = "true",
                matchIfMissing = true)
        ObjectQueryController objectQueryController(TemporalStore objects,
                                                    ObjectProvider<TemporalTables> tables) {
            return new ObjectQueryController(objects, tables.getIfAvailable());
        }
    }
}
