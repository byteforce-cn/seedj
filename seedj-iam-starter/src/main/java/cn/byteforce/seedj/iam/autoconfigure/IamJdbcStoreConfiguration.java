package cn.byteforce.seedj.iam.autoconfigure;

import cn.byteforce.seedj.infra.jdbc.iam.IamSchemaCapability;
import cn.byteforce.seedj.infra.jdbc.iam.JdbcIamAuditSink;
import cn.byteforce.seedj.infra.jdbc.iam.JdbcIamAuthorizationRepository;
import cn.byteforce.seedj.infra.jdbc.iam.JdbcIamGroupRepository;
import cn.byteforce.seedj.infra.jdbc.iam.JdbcIamPolicyRepository;
import cn.byteforce.seedj.infra.jdbc.iam.JdbcIamSigningKeyStore;
import cn.byteforce.seedj.infra.jdbc.iam.JdbcIamTokenRevoker;
import cn.byteforce.seedj.infra.jdbc.iam.JdbcIamTotpRepository;
import cn.byteforce.seedj.infra.jdbc.iam.JdbcIamUserRepository;
import cn.byteforce.seedj.infra.jdbc.iam.OAuth2SchemaCapability;
import cn.byteforce.seedj.core.security.SecurityAuditSink;
import cn.byteforce.seedj.iam.spi.IamAuthorizationRepository;
import cn.byteforce.seedj.iam.spi.IamGroupRepository;
import cn.byteforce.seedj.iam.spi.IamPolicyRepository;
import cn.byteforce.seedj.iam.spi.IamSecretProtector;
import cn.byteforce.seedj.iam.spi.IamSigningKeyStore;
import cn.byteforce.seedj.iam.spi.IamTokenRevoker;
import cn.byteforce.seedj.iam.spi.IamTotpRepository;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import cn.byteforce.seedj.id.IdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/** PostgreSQL-backed IAM store. Fails fast when the DataSource is absent. */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnBean({IamEnabledMarker.class, DataSource.class})
@ConditionalOnProperty(prefix = "seedj.iam", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@ConditionalOnProperty(prefix = "seedj.iam", name = "store", havingValue = "jdbc")
@ConditionalOnClass({JdbcTemplate.class, DataSource.class})
public class IamJdbcStoreConfiguration {

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
    @ConditionalOnProperty(prefix = "seedj.iam.jwk", name = "provider",
            havingValue = "jdbc", matchIfMissing = true)
    IamSigningKeyStore iamSigningKeyStore(JdbcTemplate jdbc, DataSource dataSource,
                                          IamSecretProtector protector,
                                          IdGenerator<String> idGenerator) {
        return new JdbcIamSigningKeyStore(jdbc, dataSource, protector, idGenerator);
    }

}
