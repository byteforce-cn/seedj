package cn.byteforce.seedj.outbox.wiring;

import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.outbox.OutboxWriter;
import cn.byteforce.seedj.outbox.spi.OutboxSink;
import cn.byteforce.seedj.outbox.spi.OutboxStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * Outbox 装配（Spring 组装与 PostgreSQL 存储同属 {@code seedj-jdbc-postgresql}）。
 *
 * <p>本类只做装配，不提供存储或投递实现：
 * <ul>
 *   <li>存储：{@code seedj-jdbc-postgresql} 的 PostgreSQL store；</li>
 *   <li>投递：由接入方提供 {@link OutboxSink}（eventbus 的实现是
 *       {@code EventBusOutboxSink}）。</li>
 * </ul>
 *
 * <p>没有 store 或 sink 时 relay 只打一条 info 日志并空转；writer 仍然存在，
 * 但 append 会明确报 {@code STORE_UNAVAILABLE}。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "seedj.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OutboxWriter outboxWriter(ObjectProvider<OutboxStore> stores) {
        return new DefaultOutboxWriter(stores);
    }

    /**
     * 让只依赖 {@code seedj-jdbc-postgresql} 的应用也能执行迁移。
     */
    @Bean
    @ConditionalOnMissingBean(name = "outboxInfrastructureInitializer")
    public OutboxInfrastructureInitializer outboxInfrastructureInitializer(
            ObjectProvider<InfrastructureCapability> capabilities) {
        return new OutboxInfrastructureInitializer(capabilities);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @DependsOn("outboxInfrastructureInitializer")
    public OutboxRelay outboxRelay(ObjectProvider<OutboxStore> outboxStore,
                                   ObjectProvider<OutboxSink> sink,
                                   OutboxProperties properties,
                                   OutboxMetrics metrics) {
        return new OutboxRelay(outboxStore, sink, properties.getRelay(), metrics);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("io.micrometer.core.instrument.MeterRegistry")
    static class NoMetricsConfiguration {
        @Bean
        @ConditionalOnMissingBean
        OutboxMetrics outboxMetrics() {
            return OutboxMetrics.noop();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class MicrometerMetricsConfiguration {
        @Bean
        @ConditionalOnMissingBean
        OutboxMetrics outboxMetrics(ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry) {
            io.micrometer.core.instrument.MeterRegistry resolved = registry.getIfAvailable();
            return resolved == null ? OutboxMetrics.noop() : new MicrometerOutboxMetrics(resolved);
        }
    }
}
