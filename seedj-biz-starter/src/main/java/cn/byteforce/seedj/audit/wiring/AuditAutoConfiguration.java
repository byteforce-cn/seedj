package cn.byteforce.seedj.audit.wiring;

import cn.byteforce.seedj.audit.AuditAnchor;
import cn.byteforce.seedj.audit.AuditAppender;
import cn.byteforce.seedj.audit.AuditChain;
import cn.byteforce.seedj.audit.AuditErrorCode;
import cn.byteforce.seedj.audit.AuditException;
import cn.byteforce.seedj.audit.spi.AuditStore;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.clock.Clocks;
import cn.byteforce.seedj.cqrs.CommandAudit;
import cn.byteforce.seedj.core.security.SecurityAuditSink;
import cn.byteforce.seedj.cqrs.TemporalCommandAudit;
import cn.byteforce.seedj.id.IdGenerator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 审计装配。
 *
 * <p>{@code enabled=true}（默认）时必须有 {@code AuditStore} 与
 * {@code PlatformTransactionManager}，否则在这里直接抛
 * {@link AuditErrorCode#STORE_MISSING} / {@link AuditErrorCode#TX_REQUIRED} 阻断启动：
 * 审计开了就必须落库，不允许静默降级。</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "seedj.audit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AuditProperties.class)
public class AuditAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock auditClock() {
        return Clocks.system();
    }

    @Bean
    @ConditionalOnMissingBean
    AuditAnchor auditAnchor() {
        return head -> {
        };
    }

    @Bean
    @ConditionalOnMissingBean
    AuditChain<String> auditChain(ObjectProvider<AuditStore<String>> stores,
                                  ObjectProvider<PlatformTransactionManager> transactions,
                                  Clock clock, AuditAnchor anchor,
                                  IdGenerator<String> idGenerator) {
        AuditStore<String> store = stores.getIfAvailable();
        if (store == null) {
            throw new AuditException(AuditErrorCode.STORE_MISSING,
                    "seedj.audit.enabled=true but no AuditStore is available; add the PostgreSQL "
                            + "module (with a DataSource), set seedj.audit.provider=memory for "
                            + "development, or set seedj.audit.enabled=false");
        }
        if (transactions.getIfAvailable() == null) {
            throw new AuditException(AuditErrorCode.TX_REQUIRED,
                    "seedj.audit.enabled=true but no PlatformTransactionManager is available; "
                            + "audit must share the command transaction");
        }
        return new AuditChain<>(store, clock, anchor, idGenerator);
    }

    @Bean
    @ConditionalOnMissingBean
    AuditAppender<String> auditAppender(AuditChain<String> chain, AuditMetrics metrics) {
        return new MetricsAuditAppender(new TransactionalAuditAppender(chain), metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    CommandAudit commandAudit(ObjectProvider<AuditAppender<String>> appenders,
                              ObjectProvider<SecurityAuditSink> securityAudit,
                              IdGenerator<String> idGenerator) {
        AuditAppender<String> appender = appenders.getIfAvailable();
        if (appender == null) {
            throw new AuditException(AuditErrorCode.STORE_MISSING,
                    "seedj.audit.enabled=true but no AuditAppender is available");
        }
        return new AnnotationCommandAudit(appender, securityAudit, idGenerator);
    }

    @Bean
    @ConditionalOnMissingBean
    TemporalCommandAudit temporalCommandAudit(ObjectProvider<AuditAppender<String>> appenders) {
        AuditAppender<String> appender = appenders.getIfAvailable();
        if (appender == null) {
            throw new AuditException(AuditErrorCode.STORE_MISSING,
                    "seedj.audit.enabled=true but no AuditAppender is available");
        }
        return new TemporalCommandAuditAdapter(appender, AuditActor::current);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "seedj.audit.anchor", name = "enabled", havingValue = "true")
    @EnableScheduling
    static class AnchoringConfiguration {
        @Bean
        @ConditionalOnMissingBean
        AuditAnchorJob auditAnchorJob(AuditChain<String> chain) {
            return new AuditAnchorJob(chain);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("io.micrometer.core.instrument.MeterRegistry")
    static class NoMetricsConfiguration {
        @Bean
        @ConditionalOnMissingBean
        AuditMetrics auditMetrics() {
            return AuditMetrics.noop();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class MicrometerMetricsConfiguration {
        @Bean
        @ConditionalOnMissingBean
        AuditMetrics auditMetrics(
                ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry) {
            io.micrometer.core.instrument.MeterRegistry resolved = registry.getIfAvailable();
            return resolved == null ? AuditMetrics.noop()
                    : new MicrometerAuditMetrics(resolved);
        }
    }
}
