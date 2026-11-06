package cn.byteforce.seedj.infra.jdbc.outbox;

import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.outbox.spi.OutboxStore;
import cn.byteforce.seedj.id.IdGenerator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * PostgreSQL outbox 存储装配。
 *
 * <p>只在有 {@link DataSource} / {@link JdbcTemplate} 时生效；没有数据库的应用
 * 不会因此启动失败（EventBus 会退化为只用 transport 的模式）。
 */
@AutoConfiguration(after = {DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@ConditionalOnClass(value = {JdbcTemplate.class, DataSource.class}, name = "org.postgresql.Driver")
@ConditionalOnBean(JdbcTemplate.class)
@ConditionalOnProperty(prefix = "seedj.outbox", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "seedj.outbox", name = "provider",
        havingValue = "postgres", matchIfMissing = true)
@EnableConfigurationProperties(PostgresOutboxProperties.class)
public class PostgresOutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JdbcMigrationRunner jdbcMigrationRunner(DataSource dataSource) {
        return new JdbcMigrationRunner(dataSource);
    }

    /**
     * 由 outbox starter 或聚合 starter 的 InfrastructureInitializer 调用，保证 outbox 表与迁移历史存在。
     */
    @Bean
    @ConditionalOnMissingBean(name = "postgresOutboxSchemaCapability")
    public InfrastructureCapability postgresOutboxSchemaCapability(JdbcMigrationRunner runner) {
        return new PostgresOutboxSchemaCapability(runner);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public OutboxStore postgresOutboxStore(JdbcTemplate jdbcTemplate,
                                           PostgresOutboxProperties properties,
                                           IdGenerator<String> idGenerator) {
        return new PostgresOutboxStore(jdbcTemplate, properties.getAppendMaxAttempts(),
                idGenerator);
    }
}
