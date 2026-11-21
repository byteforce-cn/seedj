package cn.byteforce.seedj.infra.local;

import cn.byteforce.seedj.ai.spi.DecisionAdmissionStore;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.cqrs.spi.CommandStore;
import cn.byteforce.seedj.domain.ObjectSchemaRegistry;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.infra.local.ai.InMemoryDecisionAdmissionStore;
import cn.byteforce.seedj.infra.local.cqrs.InMemoryCommandStore;
import cn.byteforce.seedj.infra.local.domain.InMemoryTemporalStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;

/**
 * 内存命令/对象存储：provider 缺省且没有 JDBC 时的默认实现。
 *
 * <p>排在 PostgreSQL 适配器之后装配：两个适配器同时在 classpath 时，JDBC 先注册， 这里靠 {@link
 * ConditionalOnMissingBean} 让位；只有显式 {@code provider=memory} 或没有 JDBC 时才生效。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration(
        afterName = {
            "cn.byteforce.seedj.starter.cqrs.wiring.TemporalCommandAutoConfiguration",
            "cn.byteforce.seedj.infra.jdbc.store.PostgresStoreAutoConfiguration"
        })
public class InMemoryStoreAutoConfiguration {

    /**
     * 内存命令存储。
     *
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
            havingValue = "memory",
            matchIfMissing = true)
    public CommandStore commandStore() {
        return new InMemoryCommandStore();
    }

    /**
     * 内存对象存储。
     *
     * @param clock 取时入口
     * @param schemas 对象 schema 注册表
     * @return 对象存储
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({Clock.class, ObjectSchemaRegistry.class})
    @ConditionalOnProperty(
            prefix = "seedj.object",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    @ConditionalOnProperty(
            prefix = "seedj.object",
            name = "provider",
            havingValue = "memory",
            matchIfMissing = true)
    public TemporalStore temporalStore(Clock clock, ObjectSchemaRegistry schemas) {
        return new InMemoryTemporalStore(clock, schemas);
    }

    /**
     * 无 DataSource 的内存准入存储；有 DataSource 时由 PostgreSQL 配置提供。
     *
     * @return 准入存储
     */
    @Bean
    @ConditionalOnMissingBean({DecisionAdmissionStore.class, DataSource.class})
    public DecisionAdmissionStore decisionAdmissionStore() {
        return new InMemoryDecisionAdmissionStore();
    }
}
