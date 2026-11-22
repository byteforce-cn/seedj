package cn.byteforce.seedj.infra.jdbc.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.byteforce.seedj.core.security.SecurityAuditSink;
import cn.byteforce.seedj.iam.IamEnabledMarker;
import cn.byteforce.seedj.iam.authz.IamBindingStore;
import cn.byteforce.seedj.iam.spi.IamAuthorizationRepository;
import cn.byteforce.seedj.iam.spi.IamGroupRepository;
import cn.byteforce.seedj.iam.spi.IamPolicyRepository;
import cn.byteforce.seedj.iam.spi.IamSecretProtector;
import cn.byteforce.seedj.iam.spi.IamSigningKeyStore;
import cn.byteforce.seedj.iam.spi.IamTokenRevoker;
import cn.byteforce.seedj.iam.spi.IamTotpRepository;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.id.IdGenerator;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL IAM 适配器：store=jdbc 时自带存储、schema 与授权绑定的装配。 */
class PostgresIamStoreAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            PostgresIamStoreAutoConfiguration.class,
                            PostgresIamAuthzAutoConfiguration.class))
                    .withBean(IamEnabledMarker.class, IamEnabledMarker::new)
                    .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                    .withBean(DataSource.class, () -> mock(DataSource.class))
                    .withBean(IamSecretProtector.class, () -> mock(IamSecretProtector.class))
                    .withBean(IdGenerator.class, () -> (IdGenerator<String>) () -> UUID.randomUUID().toString());

    @Test
    @DisplayName("store=jdbc 时装配全部 JDBC 存储、schema 迁移与 PostgreSQL 授权绑定")
    void jdbcStoreIsWired() {
        runner.withPropertyValues("seedj.iam.store=jdbc")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(IamUserRepository.class))
                                    .isInstanceOf(JdbcIamUserRepository.class);
                            assertThat(context.getBean(IamGroupRepository.class))
                                    .isInstanceOf(JdbcIamGroupRepository.class);
                            assertThat(context.getBean(IamAuthorizationRepository.class))
                                    .isInstanceOf(JdbcIamAuthorizationRepository.class);
                            assertThat(context.getBean(IamPolicyRepository.class))
                                    .isInstanceOf(JdbcIamPolicyRepository.class);
                            assertThat(context.getBean(IamTotpRepository.class))
                                    .isInstanceOf(JdbcIamTotpRepository.class);
                            assertThat(context.getBean(SecurityAuditSink.class))
                                    .isInstanceOf(JdbcIamAuditSink.class);
                            assertThat(context.getBean(IamTokenRevoker.class))
                                    .isInstanceOf(JdbcIamTokenRevoker.class);
                            assertThat(context.getBean(IamSigningKeyStore.class))
                                    .isInstanceOf(JdbcIamSigningKeyStore.class);
                            assertThat(context.getBean(IamBindingStore.class))
                                    .isInstanceOf(JdbcIamBindingStore.class);
                            assertThat(context).hasBean("postgresIamSchemaCapability");
                            assertThat(context).hasBean("postgresOAuth2SchemaCapability");
                        });
    }

    @Test
    @DisplayName("store=memory 时整体让位给内存适配器")
    void memoryStoreBacksOff() {
        runner.withPropertyValues("seedj.iam.store=memory")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(IamUserRepository.class);
                            assertThat(context).doesNotHaveBean(IamBindingStore.class);
                        });
    }

    @Test
    @DisplayName("显式 seedj.authz.provider=memory 时不装 JDBC 绑定存储")
    void explicitMemoryAuthzProviderBacksOff() {
        runner.withPropertyValues("seedj.iam.store=jdbc", "seedj.authz.provider=memory")
                .run(context -> assertThat(context).doesNotHaveBean(IamBindingStore.class));
    }

    @Test
    @DisplayName("内存 store + 显式 authz.provider=postgres 仍可用 JDBC 绑定存储")
    void explicitPostgresAuthzProviderWorksWithMemoryStore() {
        runner.withPropertyValues("seedj.iam.store=memory", "seedj.authz.provider=postgres")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(IamBindingStore.class))
                                    .isInstanceOf(JdbcIamBindingStore.class);
                            assertThat(context).doesNotHaveBean(IamUserRepository.class);
                        });
    }

    @Test
    @DisplayName("enabled=false 或缺 JdbcTemplate 时不装配")
    void switchesAndMissingJdbcBackOff() {
        runner.withPropertyValues("seedj.iam.store=jdbc", "seedj.iam.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(IamUserRepository.class));

        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                PostgresIamStoreAutoConfiguration.class,
                                PostgresIamAuthzAutoConfiguration.class))
                .withBean(IamEnabledMarker.class, IamEnabledMarker::new)
                .withPropertyValues("seedj.iam.store=jdbc")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(IamUserRepository.class);
                        });
    }

    @Test
    @DisplayName("已有存储 bean 时让位")
    void existingBeansWin() {
        IamUserRepository users = mock(IamUserRepository.class);

        runner.withPropertyValues("seedj.iam.store=jdbc")
                .withBean(IamUserRepository.class, () -> users)
                .run(context -> assertThat(context.getBean(IamUserRepository.class)).isSameAs(users));
    }
}
