package cn.byteforce.seedj.infra.local.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.byteforce.seedj.core.security.SecurityAuditSink;
import cn.byteforce.seedj.iam.IamEnabledMarker;
import cn.byteforce.seedj.iam.authz.IamAttributeSource;
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
import cn.byteforce.seedj.transit.TransitProvider;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 内存 IAM 适配器：存储、密钥保护与授权绑定都由适配器自行装配。 */
class IamMemoryStoreAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(IamMemoryStoreAutoConfiguration.class))
                    .withBean(IamEnabledMarker.class, IamEnabledMarker::new)
                    .withBean(
                            IdGenerator.class,
                            () -> (IdGenerator<String>) () -> UUID.randomUUID().toString());

    @Test
    @DisplayName("默认（store=memory）装配全部内存存储、AES 保护器与内存授权绑定")
    void memoryIsTheDefault() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(IamUserRepository.class))
                            .isInstanceOf(InMemoryIamUserRepository.class);
                    assertThat(context.getBean(IamGroupRepository.class))
                            .isInstanceOf(InMemoryIamGroupRepository.class);
                    assertThat(context.getBean(IamAuthorizationRepository.class))
                            .isInstanceOf(InMemoryIamAuthorizationRepository.class);
                    assertThat(context.getBean(IamPolicyRepository.class))
                            .isInstanceOf(InMemoryIamPolicyRepository.class);
                    assertThat(context.getBean(IamTotpRepository.class))
                            .isInstanceOf(InMemoryIamTotpRepository.class);
                    assertThat(context.getBean(SecurityAuditSink.class))
                            .isInstanceOf(InMemoryIamAuditSink.class);
                    assertThat(context.getBean(IamTokenRevoker.class))
                            .isInstanceOf(InMemoryIamTokenRevoker.class);
                    assertThat(context.getBean(IamSigningKeyStore.class))
                            .isInstanceOf(InMemoryIamSigningKeyStore.class);
                    assertThat(context.getBean(IamSecretProtector.class))
                            .isInstanceOf(AesGcmIamSecretProtector.class);
                    assertThat(context.getBean(IamBindingStore.class))
                            .isInstanceOf(InMemoryIamBindingStore.class);
                    assertThat(context.getBean(IamAttributeSource.class))
                            .isInstanceOf(InMemoryIamAttributeSource.class);
                });
    }

    @Test
    @DisplayName("store=jdbc 时内存存储整体让位；属性源不是存储，仍由本适配器提供")
    void jdbcStoreLeavesMemoryStoresToTheJdbcAdapter() {
        runner.withPropertyValues("seedj.iam.store=jdbc", "seedj.iam.secret.key=" + key())
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(IamUserRepository.class);
                            assertThat(context).doesNotHaveBean(IamSigningKeyStore.class);
                            assertThat(context).doesNotHaveBean(IamBindingStore.class);
                            assertThat(context.getBean(IamAttributeSource.class))
                                    .isInstanceOf(InMemoryIamAttributeSource.class);
                            assertThat(context.getBean(IamSecretProtector.class))
                                    .isInstanceOf(AesGcmIamSecretProtector.class);
                        });

        runner.withPropertyValues(
                        "seedj.iam.store=jdbc",
                        "seedj.iam.secret.key=" + key(),
                        "seedj.authz.provider=memory")
                .run(
                        context -> {
                            assertThat(context.getBean(IamBindingStore.class))
                                    .isInstanceOf(InMemoryIamBindingStore.class);
                            assertThat(context.getBean(IamAttributeSource.class))
                                    .isInstanceOf(InMemoryIamAttributeSource.class);
                        });
    }

    @Test
    @DisplayName("有 TransitProvider 时默认用 Transit 保护密钥；显式 aes 时退回 AES")
    void transitWinsOverAesWhenAvailable() {
        runner.withBean(TransitProvider.class, () -> mock(TransitProvider.class))
                .run(
                        context ->
                                assertThat(context.getBean(IamSecretProtector.class))
                                        .isInstanceOf(TransitIamSecretProtector.class));

        runner.withBean(TransitProvider.class, () -> mock(TransitProvider.class))
                .withPropertyValues("seedj.iam.secret.provider=aes")
                .run(
                        context ->
                                assertThat(context.getBean(IamSecretProtector.class))
                                        .isInstanceOf(AesGcmIamSecretProtector.class));
    }

    @Test
    @DisplayName("store=jdbc 且没有密钥时启动期失败，避免重启后 JWK/TOTP 失效")
    void jdbcStoreRequiresAStableKey() {
        runner.withPropertyValues("seedj.iam.store=jdbc")
                .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    }

    @Test
    @DisplayName("已有 bean 不覆盖，enabled=false 时整体退出，jwk.provider=jdbc 时不装内存 JWK")
    void switchesAndExistingBeansAreHonoured() {
        IamUserRepository users = new InMemoryIamUserRepository();
        runner.withBean(IamUserRepository.class, () -> users)
                .run(
                        context ->
                                assertThat(context.getBean(IamUserRepository.class))
                                        .isSameAs(users));

        runner.withPropertyValues("seedj.iam.enabled=false")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(IamUserRepository.class);
                            assertThat(context).doesNotHaveBean(IamSecretProtector.class);
                        });

        runner.withPropertyValues("seedj.iam.jwk.provider=jdbc")
                .run(context -> assertThat(context).doesNotHaveBean(IamSigningKeyStore.class));
    }

    private static String key() {
        return java.util.Base64.getEncoder().encodeToString(new byte[32]);
    }

    @Test
    @DisplayName("没有 @EnableIamServer 标记时适配器保持惰性")
    void adapterIsInertWithoutTheEnablementMarker() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IamMemoryStoreAutoConfiguration.class))
                .withBean(
                        IdGenerator.class,
                        () -> (IdGenerator<String>) () -> UUID.randomUUID().toString())
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(IamUserRepository.class);
                            assertThat(context).doesNotHaveBean(IamSecretProtector.class);
                        });
    }
}
