package cn.byteforce.seedj.infra.jdbc.audit;

import cn.byteforce.seedj.audit.spi.AuditStore;
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

/** PostgreSQL 审计存储装配：只在有 DataSource 时生效。 */
@AutoConfiguration(after = {DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@ConditionalOnClass(
        value = {JdbcTemplate.class, DataSource.class},
        name = "org.postgresql.Driver")
@ConditionalOnBean(JdbcTemplate.class)
@ConditionalOnProperty(
        prefix = "seedj.audit",
        name = "provider",
        havingValue = "postgres",
        matchIfMissing = true)
public class PostgresAuditAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AuditStore<String> auditStore(JdbcTemplate jdbcTemplate) {
        return new JdbcAuditStore(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(name = "postgresAuditSchemaCapability")
    public InfrastructureCapability postgresAuditSchemaCapability(
            DataSource dataSource, ObjectProvider<JdbcMigrationRunner> runners) {
        return new AuditSchemaCapability(
                runners.getIfAvailable(() -> new JdbcMigrationRunner(dataSource)));
    }
}
