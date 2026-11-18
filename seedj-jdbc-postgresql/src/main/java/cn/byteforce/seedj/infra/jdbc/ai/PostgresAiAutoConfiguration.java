package cn.byteforce.seedj.infra.jdbc.ai;

import cn.byteforce.seedj.ai.spi.DecisionLedger;
import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
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
 * PostgreSQL 决策账本装配：只在有 DataSource 时生效。
 *
 * <p>{@code seedj.ai.provider=postgres}（缺省）装配数据库账本；切到内存实现时设成别的值，本配置整体退出。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration(after = {DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@ConditionalOnClass(
        value = {JdbcTemplate.class, DataSource.class},
        name = "org.postgresql.Driver")
@ConditionalOnBean(JdbcTemplate.class)
@ConditionalOnProperty(
        prefix = "seedj.ai",
        name = "provider",
        havingValue = "postgres",
        matchIfMissing = true)
public class PostgresAiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DecisionLedger decisionLedger(DataSource dataSource) {
        return new PostgresDecisionLedger(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(name = "postgresAiSchemaCapability")
    public InfrastructureCapability postgresAiSchemaCapability(
            DataSource dataSource, ObjectProvider<JdbcMigrationRunner> runners) {
        return new AiSchemaCapability(
                runners.getIfAvailable(() -> new JdbcMigrationRunner(dataSource)));
    }
}
