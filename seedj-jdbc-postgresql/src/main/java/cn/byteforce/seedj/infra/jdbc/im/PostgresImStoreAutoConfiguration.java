package cn.byteforce.seedj.infra.jdbc.im;

import cn.byteforce.seedj.im.ImEnabledMarker;
import cn.byteforce.seedj.im.spi.ImCardActionStore;
import cn.byteforce.seedj.im.spi.ImConversationStore;
import cn.byteforce.seedj.im.spi.ImCursorStore;
import cn.byteforce.seedj.im.spi.ImMessageStore;
import cn.byteforce.seedj.im.spi.ImSequenceAllocator;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL IM 存储：{@code seedj.im.store=jdbc} 时由适配器自带装配。
 *
 * <p>starter 只消费 {@code Im*Store} SPI；本配置用 {@code seedj.im.store} 与 {@link JdbcTemplate}
 * 的存在性竞争，{@code store=memory} 时整体退出。显式 {@code store=jdbc} 但缺数据源的失败由 starter 的
 * 前置检查报告，不静默退回内存。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration(
        after = {DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class},
        afterName = "cn.byteforce.seedj.starter.im.ImStoreAutoConfiguration")
@ConditionalOnBean({ImEnabledMarker.class, JdbcTemplate.class})
@ConditionalOnProperty(
        prefix = "seedj.im",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@ConditionalOnProperty(prefix = "seedj.im", name = "store", havingValue = "jdbc")
@ConditionalOnClass({JdbcTemplate.class, DataSource.class})
public class PostgresImStoreAutoConfiguration {

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

    @Bean
    @ConditionalOnMissingBean
    ImCardActionStore imCardActionStore(JdbcTemplate jdbc) {
        return new JdbcImCardActionStore(jdbc);
    }
}
