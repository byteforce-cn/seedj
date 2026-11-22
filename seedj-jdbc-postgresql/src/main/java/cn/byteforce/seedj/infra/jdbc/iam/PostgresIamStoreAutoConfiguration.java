package cn.byteforce.seedj.infra.jdbc.iam;

import cn.byteforce.seedj.core.security.SecurityAuditSink;
import cn.byteforce.seedj.iam.IamEnabledMarker;
import cn.byteforce.seedj.iam.spi.IamAuthorizationRepository;
import cn.byteforce.seedj.iam.spi.IamGroupRepository;
import cn.byteforce.seedj.iam.spi.IamPolicyRepository;
import cn.byteforce.seedj.iam.spi.IamSecretProtector;
import cn.byteforce.seedj.iam.spi.IamSigningKeyStore;
import cn.byteforce.seedj.iam.spi.IamTokenRevoker;
import cn.byteforce.seedj.iam.spi.IamTotpRepository;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
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
 * PostgreSQL IAM 存储：{@code seedj.iam.store=jdbc} 时由适配器自带装配。
 *
 * <p>starter 不再在编译期依赖本模块：它只消费 {@code IamUserRepository} 等 SPI，适配器用
 * {@code seedj.iam.store} 与 {@link JdbcTemplate} 的存在性竞争。{@code store=memory}（缺省）时本配置整体
 * 退出，由内存适配器接管。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration(
        after = {DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class},
        afterName = "cn.byteforce.seedj.starter.iam.autoconfigure.IamAutoConfiguration")
@ConditionalOnBean({IamEnabledMarker.class, JdbcTemplate.class})
@ConditionalOnProperty(
        prefix = "seedj.iam",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@ConditionalOnProperty(prefix = "seedj.iam", name = "store", havingValue = "jdbc")
@ConditionalOnClass({JdbcTemplate.class, DataSource.class})
public class PostgresIamStoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    JdbcMigrationRunner iamJdbcMigrationRunner(DataSource dataSource) {
        return new JdbcMigrationRunner(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(name = "postgresIamSchemaCapability")
    IamSchemaCapability postgresIamSchemaCapability(JdbcMigrationRunner runner) {
        return new IamSchemaCapability(runner);
    }

    @Bean
    @ConditionalOnMissingBean(name = "postgresOAuth2SchemaCapability")
    OAuth2SchemaCapability postgresOAuth2SchemaCapability(JdbcMigrationRunner runner) {
        return new OAuth2SchemaCapability(runner);
    }

    @Bean
    @ConditionalOnMissingBean(IamUserRepository.class)
    IamUserRepository iamUserRepository(JdbcTemplate jdbc) {
        return new JdbcIamUserRepository(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean(IamGroupRepository.class)
    IamGroupRepository iamGroupRepository(JdbcTemplate jdbc, DataSource dataSource) {
        return new JdbcIamGroupRepository(jdbc, dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(IamAuthorizationRepository.class)
    IamAuthorizationRepository iamAuthorizationRepository(JdbcTemplate jdbc) {
        return new JdbcIamAuthorizationRepository(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean(IamPolicyRepository.class)
    IamPolicyRepository iamPolicyRepository(JdbcTemplate jdbc) {
        return new JdbcIamPolicyRepository(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean(IamTotpRepository.class)
    IamTotpRepository iamTotpRepository(JdbcTemplate jdbc) {
        return new JdbcIamTotpRepository(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean(SecurityAuditSink.class)
    SecurityAuditSink iamAuditSink(JdbcTemplate jdbc) {
        return new JdbcIamAuditSink(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean(IamTokenRevoker.class)
    IamTokenRevoker iamTokenRevoker(JdbcTemplate jdbc) {
        return new JdbcIamTokenRevoker(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean(IamSigningKeyStore.class)
    @ConditionalOnProperty(
            prefix = "seedj.iam.jwk",
            name = "provider",
            havingValue = "jdbc",
            matchIfMissing = true)
    IamSigningKeyStore iamSigningKeyStore(
            JdbcTemplate jdbc,
            DataSource dataSource,
            IamSecretProtector protector,
            IdGenerator<String> idGenerator) {
        return new JdbcIamSigningKeyStore(jdbc, dataSource, protector, idGenerator);
    }
}
