package cn.byteforce.seedj.starter.cqrs.wiring;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.clock.Clocks;
import cn.byteforce.seedj.core.security.CallerIdCodec;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.cqrs.CommandCatalog;
import cn.byteforce.seedj.cqrs.CommandPolicy;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.cqrs.CommandValidator;
import cn.byteforce.seedj.cqrs.TemporalCommandAudit;
import cn.byteforce.seedj.cqrs.spi.CommandStore;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ObjectSchemaRegistry;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.id.Uuid7IdGenerator;
import cn.byteforce.seedj.lock.LockProvider;
import cn.byteforce.seedj.outbox.OutboxWriter;
import cn.byteforce.seedj.starter.autoconfigure.BizEnabledMarker;
import cn.byteforce.seedj.starter.cqrs.web.CommandController;
import cn.byteforce.seedj.starter.validation.BeanValidationCommandValidator;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 命令运行时、对象查询与 HTTP 面的默认装配。
 *
 * <p>provider 选择是显式的：{@code seedj.command.provider} 与 {@code seedj.object.provider} 缺省时按依赖推断（有 JDBC
 * 模板则 PostgreSQL， 否则内存）；显式指定 PostgreSQL 但缺依赖时启动失败，不静默降级。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration(
        afterName = {
            "cn.byteforce.seedj.starter.security.BizSecurityAutoConfiguration",
            "cn.byteforce.seedj.starter.audit.AuditAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
        })
@ConditionalOnBean(BizEnabledMarker.class)
@EnableConfigurationProperties(CommandRuntimeProperties.class)
public class TemporalCommandAutoConfiguration {

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
     * 对象类型到字段声明的注册表。
     *
     * <p>字段先来自 {@link cn.byteforce.seedj.module.ObjectModuleRegistry}（业务声明）， 再合并显式 {@link
     * ObjectSchema} bean。同一对象类型出现两次直接失败：两份字段声明 会立刻漂移，宁可启动失败也不静默选一份。
     *
     * @param modules 模块注册表
     * @param schemas 显式 schema bean
     * @return 注册表
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "seedj.object",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public ObjectSchemaRegistry objectSchemaRegistry(
            ObjectProvider<cn.byteforce.seedj.module.ObjectModuleRegistry> modules,
            ObjectProvider<ObjectSchema> schemas) {
        Map<String, ObjectSchema> declared = new LinkedHashMap<>();
        modules.ifAvailable(
                registry ->
                        registry.all()
                                .forEach(
                                        model -> declared.put(model.objectType(), model.schema())));
        schemas.orderedStream()
                .forEach(
                        schema -> {
                            ObjectSchema previous =
                                    declared.putIfAbsent(schema.objectType(), schema);
                            if (previous != null) {
                                throw new IllegalStateException(
                                        "duplicate objectType across module declarations"
                                                + " and ObjectSchema beans: "
                                                + schema.objectType());
                            }
                        });
        return declared.isEmpty()
                ? ObjectSchemaRegistry.empty()
                : new ObjectSchemaRegistry(List.copyOf(declared.values()));
    }

    /**
     * 命令运行时。
     *
     * @param commands 命令存储
     * @param objects 对象存储
     * @param tx 事务边界
     * @param locks 锁
     * @param outbox outbox
     * @param clock 取时入口
     * @param ids 标识生成器
     * @param audit 临时命令审计 seam
     * @return 运行时
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "seedj.command",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public CommandRuntime commandRuntime(
            CommandStore commands,
            TemporalStore objects,
            ObjectProvider<TransactionRunner> tx,
            ObjectProvider<LockProvider> locks,
            ObjectProvider<OutboxWriter> outbox,
            Clock clock,
            @Qualifier("commandIdGenerator") IdGenerator<UUID> ids,
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
        return new CommandRuntime(
                new CommandRuntime.Options(
                        transactionRunner,
                        lockProvider,
                        Duration.ofSeconds(30),
                        clock,
                        ids,
                        commands,
                        objects,
                        outboxWriter,
                        audit.getIfAvailable(TemporalCommandAudit::noop),
                        tools.jackson.databind.json.JsonMapper.builder().build(),
                        properties.getPendingLease()));
    }

    private static void requireDependency(Object dependency, String name) {
        if (dependency == null) {
            throw new IllegalStateException(
                    "seedj.command requires a "
                            + name
                            + "; configure it, or set seedj.command.degraded-mode=true only for"
                            + " local/test");
        }
    }

    /**
     * 降级开关守卫。
     *
     * @param environment Spring 环境
     * @return 启动期守卫
     */
    @Bean
    @ConditionalOnMissingBean
    public cn.byteforce.seedj.starter.config.DegradedModeGuard degradedModeGuard(
            Environment environment) {
        return new cn.byteforce.seedj.starter.config.DegradedModeGuard(environment);
    }

    /**
     * 命令级授权策略：唯一入口是 {@link cn.byteforce.seedj.security.AuthorizationDecisionPoint}。
     *
     * <p>没有决策点 bean 时上下文启动失败：不允许悄悄回退到“只看 JWT claim”的旧路径。
     *
     * @param decisions 授权决策点
     * @param clock 业务时点
     * @return 权限策略
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "seedj.command",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public CommandPolicy commandPolicy(
            cn.byteforce.seedj.security.AuthorizationDecisionPoint decisions,
            Clock clock,
            ObjectProvider<cn.byteforce.seedj.core.security.SecurityAuditSink> audit) {
        return CommandPolicy.decisionPoint(decisions, clock, audit.getIfAvailable());
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
                ? CommandValidator.none()
                : new BeanValidationCommandValidator(delegate);
    }

    /** Web 面：命令与对象查询控制器。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class WebConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(
                prefix = "seedj.command",
                name = "enabled",
                havingValue = "true",
                matchIfMissing = true)
        CommandController commandController(
                CommandCatalog catalog,
                CommandStore commands,
                ObjectProvider<CallerProvider> callers,
                CallerIdCodec callerIds) {
            CallerProvider provider = callers.getIfAvailable();
            return new CommandController(
                    catalog, commands, provider == null ? Optional::empty : provider, callerIds);
        }
    }
}
