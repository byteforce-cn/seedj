package cn.byteforce.seedj.starter.cqrs.wiring;

import cn.byteforce.seedj.cqrs.CommandAudit;
import cn.byteforce.seedj.cqrs.CommandInterceptor;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.lock.LockProvider;
import cn.byteforce.seedj.starter.audit.DisabledCommandAudit;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 命令模板装配：业务只注入 {@link CommandAgents}，由框架提供事务边界、 锁、审计钩子与指标拦截器。
 *
 * <p>本类在 {@code AuditAutoConfiguration} 之后处理：有审计实现时用真实实现， 没有时退回 {@link
 * DisabledCommandAudit}（对已标注的命令 WARN 一次，不静默）。
 */
@AutoConfiguration(afterName = "cn.byteforce.seedj.starter.audit.AuditAutoConfiguration")
@EnableConfigurationProperties(CommandRuntimeProperties.class)
public class CommandAgentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CommandAgentAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public TransactionRunner seedjTransactionRunner(
            ObjectProvider<PlatformTransactionManager> managers,
            CommandRuntimeProperties properties) {
        PlatformTransactionManager manager = managers.getIfAvailable();
        if (manager == null) {
            if (!properties.isDegradedMode()) {
                throw new IllegalStateException(
                        "seedj.command requires a"
                                + " PlatformTransactionManager; configure it, or set"
                                + " seedj.command.degraded-mode=true only for local/test");
            }
            log.warn("seedj: no PlatformTransactionManager; commands run without a transaction");
            return TransactionRunner.direct();
        }
        return new SpringTransactionRunner(manager);
    }

    @Bean
    @ConditionalOnMissingBean
    public CommandAgents commandAgents(
            ObjectProvider<TransactionRunner> transactionRunner,
            ObjectProvider<LockProvider> lockProvider,
            ObjectProvider<CommandAudit> audit,
            ObjectProvider<CommandMetrics> metrics,
            ObjectProvider<CommandInterceptor<?>> interceptors,
            CommandRuntimeProperties properties) {
        List<CommandInterceptor<?>> ordered = new ArrayList<>();
        interceptors.orderedStream().forEach(ordered::add);
        LockProvider locks = lockProvider.getIfAvailable();
        if (locks == null && !properties.isDegradedMode()) {
            throw new IllegalStateException(
                    "seedj.command requires a LockProvider; configure it,"
                            + " or set seedj.command.degraded-mode=true only for local/test");
        }
        return new CommandAgents(
                transactionRunner.getIfAvailable(TransactionRunner::direct),
                locks == null ? LockProvider.disabled() : locks,
                audit.getIfAvailable(DisabledCommandAudit::new),
                metrics.getIfAvailable(CommandMetrics::noop),
                ordered);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("io.micrometer.core.instrument.MeterRegistry")
    static class NoMetricsConfiguration {
        @Bean
        @ConditionalOnMissingBean
        CommandMetrics commandMetrics() {
            return CommandMetrics.noop();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class MicrometerMetricsConfiguration {
        @Bean
        @ConditionalOnMissingBean
        CommandMetrics commandMetrics(
                ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry) {
            io.micrometer.core.instrument.MeterRegistry resolved = registry.getIfAvailable();
            return resolved == null
                    ? CommandMetrics.noop()
                    : new MicrometerCommandMetrics(resolved);
        }
    }
}
