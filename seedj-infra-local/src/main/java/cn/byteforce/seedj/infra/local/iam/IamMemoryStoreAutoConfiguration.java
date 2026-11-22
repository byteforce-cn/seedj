package cn.byteforce.seedj.infra.local.iam;

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
import java.security.SecureRandom;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 内存 IAM 适配器：存储、密钥保护与授权绑定的选择都归适配器所有。
 *
 * <p>starter 只消费 {@code IamUserRepository} 等 SPI；本模块在 {@code seedj.iam.store=memory}（缺省）时提供
 * 进程内实现，并保留 Transit/AES 两级密钥保护（Transit 优先，AES 兜底）。{@code store=jdbc} 时内存存储 整体让位给 PostgreSQL
 * 适配器，只有密钥保护器继续生效——它是 JWK/TOTP 材料的加密原语，与存储选型无关。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnBean(IamEnabledMarker.class)
@ConditionalOnProperty(
        prefix = "seedj.iam",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class IamMemoryStoreAutoConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(IamMemoryStoreAutoConfiguration.class);

    /**
     * 密钥保护器：{@code seedj.iam.secret.provider=transit}（缺省）且有 TransitProvider 时用 Transit， 否则 AES-GCM
     * 兜底。
     */
    @Configuration(proxyBeanMethods = false)
    static class SecretProtectorConfiguration {

        private static final SecureRandom SECURE_RANDOM = new SecureRandom();

        @Bean
        @ConditionalOnMissingBean(IamSecretProtector.class)
        IamSecretProtector iamSecretProtector(
                ObjectProvider<TransitProvider> transit,
                @Value("${seedj.iam.secret.provider:transit}") String provider,
                @Value("${seedj.iam.secret.key:}") String configured,
                @Value("${seedj.iam.secret.key-name:iam-aes}") String keyName,
                @Value("${seedj.iam.store:memory}") String store) {
            TransitProvider available = transit.getIfAvailable();
            if (available != null && "transit".equalsIgnoreCase(provider)) {
                return new TransitIamSecretProtector(available, "iam-secrets");
            }
            byte[] key;
            if (configured != null && !configured.isBlank()) {
                key = Base64.getDecoder().decode(configured);
            } else {
                if ("jdbc".equalsIgnoreCase(store)) {
                    throw new IllegalStateException(
                            "seedj.iam.secret.key (or a TransitProvider) is required when "
                                    + "seedj.iam.store=jdbc; otherwise JWK/TOTP material cannot "
                                    + "survive a restart");
                }
                key = new byte[32];
                SECURE_RANDOM.nextBytes(key);
                log.warn(
                        "seedj.iam.secret.key is not set; generated an ephemeral AES key. "
                                + "Set a stable key or enable Transit for restarts.");
            }
            return new AesGcmIamSecretProtector(key, keyName);
        }
    }

    /** Memory store: zero external dependencies, for tests and local runs. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
            prefix = "seedj.iam",
            name = "store",
            havingValue = "memory",
            matchIfMissing = true)
    static class MemoryStoreConfiguration {
        @Bean
        @ConditionalOnMissingBean(IamUserRepository.class)
        IamUserRepository iamUserRepository() {
            return new InMemoryIamUserRepository();
        }

        @Bean
        @ConditionalOnMissingBean(IamGroupRepository.class)
        IamGroupRepository iamGroupRepository() {
            return new InMemoryIamGroupRepository();
        }

        @Bean
        @ConditionalOnMissingBean(IamAuthorizationRepository.class)
        IamAuthorizationRepository iamAuthorizationRepository() {
            return new InMemoryIamAuthorizationRepository();
        }

        @Bean
        @ConditionalOnMissingBean(IamPolicyRepository.class)
        IamPolicyRepository iamPolicyRepository() {
            return new InMemoryIamPolicyRepository();
        }

        @Bean
        @ConditionalOnMissingBean(IamTotpRepository.class)
        IamTotpRepository iamTotpRepository() {
            return new InMemoryIamTotpRepository();
        }

        @Bean
        @ConditionalOnMissingBean(SecurityAuditSink.class)
        SecurityAuditSink iamAuditSink() {
            return new InMemoryIamAuditSink();
        }

        @Bean
        @ConditionalOnMissingBean(IamTokenRevoker.class)
        IamTokenRevoker iamTokenRevoker() {
            return new InMemoryIamTokenRevoker();
        }

        @Bean
        @ConditionalOnMissingBean(IamSigningKeyStore.class)
        @ConditionalOnProperty(
                prefix = "seedj.iam.jwk",
                name = "provider",
                havingValue = "memory",
                matchIfMissing = true)
        IamSigningKeyStore iamSigningKeyStore(IdGenerator<String> idGenerator) {
            return new InMemoryIamSigningKeyStore(idGenerator);
        }
    }

    /** 授权契约的存储选择：memory 是缺省（或 {@code seedj.iam.store=memory}）。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
            prefix = "seedj.authz",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    static class MemoryAuthzConfiguration {

        @Bean
        @ConditionalOnMissingBean(IamBindingStore.class)
        @Conditional(MemoryBindingProviderCondition.class)
        IamBindingStore iamBindingStore() {
            return new InMemoryIamBindingStore();
        }

        @Bean
        @ConditionalOnMissingBean(IamAttributeSource.class)
        IamAttributeSource iamAttributeSource() {
            return new InMemoryIamAttributeSource();
        }
    }

    /**
     * {@code seedj.authz.provider} 缺省时跟随 {@code seedj.iam.store}；显式值优先。
     *
     * <p>与 JDBC 适配器的条件是互补的，因此两个适配器同时在 classpath 时只会有一个绑定存储。
     */
    static final class MemoryBindingProviderCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String provider = context.getEnvironment().getProperty("seedj.authz.provider");
            if (provider != null && !provider.isBlank()) {
                return "memory".equals(provider);
            }
            return !"jdbc"
                    .equals(context.getEnvironment().getProperty("seedj.iam.store", "memory"));
        }
    }
}
