package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.im.spi.ImConversationStore;
import cn.byteforce.seedj.im.spi.ImCursorStore;
import cn.byteforce.seedj.im.spi.ImMessageStore;
import cn.byteforce.seedj.im.spi.ImSequenceAllocator;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.infra.jdbc.im.ImSchemaCapability;
import cn.byteforce.seedj.infra.jdbc.im.JdbcImConversationStore;
import cn.byteforce.seedj.infra.jdbc.im.JdbcImCursorStore;
import cn.byteforce.seedj.infra.jdbc.im.JdbcImMessageStore;
import cn.byteforce.seedj.infra.jdbc.im.JdbcImSequenceAllocator;
import cn.byteforce.seedj.infra.local.im.AtomicLongSequenceAllocator;
import cn.byteforce.seedj.infra.local.im.InMemoryImConversationStore;
import cn.byteforce.seedj.infra.local.im.InMemoryImCursorStore;
import cn.byteforce.seedj.infra.local.im.InMemoryImMessageStore;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * IM 存储装配：{@code seedj.im.store} 是唯一的开关。
 *
 * <p>两个分支互斥：{@code memory}（默认）用进程内实现，{@code jdbc} 用 PostgreSQL 实现并要求容器里 有 {@link
 * JdbcTemplate}。{@code jdbc} 模式下未就绪的容器会在启动期直接失败，不做「悄悄退回内存」。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration(
        after = {DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class},
        afterName = "cn.byteforce.seedj.starter.cqrs.wiring.TemporalCommandAutoConfiguration")
@ConditionalOnBean(ImEnabledMarker.class)
@ConditionalOnProperty(
        prefix = "seedj.im",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ImStoreAutoConfiguration {

    /**
     * IM 取时入口；容器里已有 {@code Clock} 时直接复用（业务命令的契约时钟）。
     *
     * @return 时钟
     */
    @Bean
    @ConditionalOnMissingBean
    Clock imClock() {
        return Clock.systemUTC();
    }

    /** 内存分支。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
            prefix = "seedj.im",
            name = "store",
            havingValue = "memory",
            matchIfMissing = true)
    static class MemoryStores {

        @Bean
        @ConditionalOnMissingBean
        ImSequenceAllocator imSequenceAllocator() {
            return new AtomicLongSequenceAllocator();
        }

        @Bean
        @ConditionalOnMissingBean
        ImConversationStore imConversationStore(ImSequenceAllocator numbers, Clock clock) {
            return new InMemoryImConversationStore(numbers, clock);
        }

        @Bean
        @ConditionalOnMissingBean
        ImMessageStore imMessageStore(Clock clock) {
            return new InMemoryImMessageStore(clock);
        }

        @Bean
        @ConditionalOnMissingBean
        ImCursorStore imCursorStore() {
            return new InMemoryImCursorStore();
        }
    }

    /**
     * {@code jdbc} 模式下缺数据源：显式失败。
     *
     * <p>不静默退回内存——那样消息在重启后消失，而运维看不出任何异常。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "seedj.im", name = "store", havingValue = "jdbc")
    @ConditionalOnMissingBean(JdbcTemplate.class)
    static class JdbcWithoutDataSource {

        @Bean
        JdbcImStoreMisconfigured imStoreMisconfigured() {
            throw new IllegalStateException(
                    "seedj.im.store=jdbc requires a DataSource/JdbcTemplate; configure"
                            + " spring.datasource or set seedj.im.store=memory");
        }
    }

    /** 缺数据源时的诊断标记，不会真正被创建。 */
    static final class JdbcImStoreMisconfigured {}

    /** PostgreSQL 分支。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({JdbcTemplate.class, DataSource.class})
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnProperty(prefix = "seedj.im", name = "store", havingValue = "jdbc")
    static class JdbcStores {

        @Bean
        @ConditionalOnMissingBean
        JdbcMigrationRunner imJdbcMigrationRunner(DataSource dataSource) {
            return new JdbcMigrationRunner(dataSource);
        }

        @Bean
        @ConditionalOnMissingBean(name = "postgresImSchemaCapability")
        ImSchemaCapability postgresImSchemaCapability(JdbcMigrationRunner runner) {
            return new ImSchemaCapability(runner);
        }

        @Bean
        @ConditionalOnMissingBean
        ImSequenceAllocator imSequenceAllocator(JdbcTemplate jdbc) {
            return new JdbcImSequenceAllocator(jdbc);
        }

        @Bean
        @ConditionalOnMissingBean
        ImConversationStore imConversationStore(
                JdbcTemplate jdbc, ImSequenceAllocator numbers, Clock clock) {
            return new JdbcImConversationStore(jdbc, numbers, clock);
        }

        @Bean
        @ConditionalOnMissingBean
        ImMessageStore imMessageStore(JdbcTemplate jdbc, Clock clock) {
            return new JdbcImMessageStore(jdbc, clock);
        }

        @Bean
        @ConditionalOnMissingBean
        ImCursorStore imCursorStore(JdbcTemplate jdbc, Clock clock) {
            return new JdbcImCursorStore(jdbc, clock);
        }
    }
}
