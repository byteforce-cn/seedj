package cn.byteforce.seedj.lock.jdbc;

import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.lock.LockProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * PostgreSQL 锁装配：显式 {@code seedj.lock.postgres.enabled=true} 才生效。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnClass({JdbcTemplate.class, DataSource.class})
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "seedj.lock.postgres", name = "enabled", havingValue = "true")
public class PostgresLockAutoConfiguration {

    /**
     * 数据库锁 provider；已有其它 provider 时不覆盖。
     *
     * @param jdbc JDBC 模板
     * @return provider
     */
    @Bean
    @ConditionalOnMissingBean(LockProvider.class)
    public LockProvider postgresLockProvider(JdbcTemplate jdbc) {
        return new PostgresLockProvider(jdbc);
    }

    /**
     * 锁表迁移能力。
     *
     * @param dataSource 数据源
     * @param runners    迁移执行器
     * @return 能力
     */
    @Bean
    public InfrastructureCapability postgresLockSchemaCapability(
            DataSource dataSource, org.springframework.beans.factory.ObjectProvider<JdbcMigrationRunner> runners) {
        return new PostgresLockSchemaCapability(
                runners.getIfAvailable(() -> new JdbcMigrationRunner(dataSource)));
    }
}
