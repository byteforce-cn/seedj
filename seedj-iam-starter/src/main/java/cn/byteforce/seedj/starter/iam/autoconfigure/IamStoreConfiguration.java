package cn.byteforce.seedj.starter.iam.autoconfigure;

import cn.byteforce.seedj.starter.iam.config.IamProperties;
import cn.byteforce.seedj.starter.iam.identity.IamBootstrapRunner;
import cn.byteforce.seedj.starter.iam.identity.IamIdentityService;
import cn.byteforce.seedj.starter.iam.identity.IamTotpService;
import cn.byteforce.seedj.starter.iam.identity.IamMfaChallengeService;
import cn.byteforce.seedj.infra.local.iam.AesGcmIamSecretProtector;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAuditSink;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAuthorizationRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamGroupRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamPolicyRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamSigningKeyStore;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamTokenRevoker;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamTotpRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamUserRepository;
import cn.byteforce.seedj.infra.local.iam.TransitIamSecretProtector;
import cn.byteforce.seedj.core.security.SecurityAuditSink;
import cn.byteforce.seedj.iam.spi.IamAuthorizationRepository;
import cn.byteforce.seedj.iam.spi.IamGroupRepository;
import cn.byteforce.seedj.iam.spi.IamPasswordHasher;
import cn.byteforce.seedj.iam.spi.IamPolicyRepository;
import cn.byteforce.seedj.iam.spi.IamSecretProtector;
import cn.byteforce.seedj.iam.spi.IamSigningKeyStore;
import cn.byteforce.seedj.iam.spi.IamTokenRevoker;
import cn.byteforce.seedj.iam.spi.IamTotpRepository;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.transit.TransitProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;

/** Store selection and the pure service graph shared by memory and JDBC modes. */
@AutoConfiguration
@ConditionalOnBean(IamEnabledMarker.class)
@ConditionalOnProperty(prefix = "seedj.iam", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class IamStoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IamStoreConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public Clock iamClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    public org.springframework.security.crypto.password.PasswordEncoder iamPasswordEncoder() {
        return org.springframework.security.crypto.factory.PasswordEncoderFactories
                .createDelegatingPasswordEncoder();
    }

    @Bean
    @ConditionalOnMissingBean
    public IamPasswordHasher iamPasswordHasher(
            org.springframework.security.crypto.password.PasswordEncoder encoder) {
        return new cn.byteforce.seedj.starter.iam.identity.IamPasswordHasherAdapter(encoder);
    }

    @Bean
    @ConditionalOnMissingBean
    public IamIdentityService iamIdentityService(IamUserRepository users,
                                                 IamPasswordHasher hasher,
                                                 IamTokenRevoker revoker,
                                                 SecurityAuditSink audit,
                                                 IdGenerator<String> idGenerator) {
        return new IamIdentityService(users, hasher, revoker, audit, idGenerator);
    }

    @Bean
    @ConditionalOnMissingBean
    public cn.byteforce.seedj.starter.iam.identity.IamAuthorizationService iamAuthorizationService(
            IamAuthorizationRepository authorization, IamGroupRepository groups,
            IamProperties properties) {
        return new cn.byteforce.seedj.starter.iam.identity.IamAuthorizationService(
                authorization, groups, properties.rbac().cacheTtl());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "seedj.iam.mfa.totp", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public IamTotpService iamTotpService(IamTotpRepository totp, IamUserRepository users,
                                         IamSecretProtector protector, IamPasswordHasher hasher,
                                         IamProperties properties, SecurityAuditSink audit,
                                         Clock clock, IdGenerator<String> idGenerator) {
        return new IamTotpService(totp, users, protector, hasher, properties.mfa().totp(),
                audit, clock, idGenerator);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "seedj.iam.native-auth", name = "enabled",
            havingValue = "true")
    public IamMfaChallengeService iamMfaChallengeService(IamTotpRepository totp,
                                                          IamProperties properties,
                                                          Clock clock,
                                                          IdGenerator<String> idGenerator) {
        return new IamMfaChallengeService(totp, properties.nativeAuth(), clock, idGenerator);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "seedj.iam.mfa.totp", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public cn.byteforce.seedj.starter.iam.identity.IamMfaAttemptLimiter iamMfaAttemptLimiter(Clock clock) {
        return new cn.byteforce.seedj.starter.iam.identity.IamMfaAttemptLimiter(5,
                java.time.Duration.ofMinutes(5), clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public IamBootstrapRunner iamBootstrapRunner(IamProperties properties,
                                                 IamUserRepository users,
                                                 IamAuthorizationRepository authorization,
                                                 IamIdentityService identities,
                                                 IdGenerator<String> idGenerator) {
        return new IamBootstrapRunner(properties.admin(), users, authorization, identities,
                idGenerator);
    }

    /** Prefer Transit when it is on the classpath and enabled; otherwise AES-GCM. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "seedj.iam.secret", name = "provider",
            havingValue = "transit", matchIfMissing = true)
    static class TransitSecretConfiguration {
        @Bean
        @ConditionalOnMissingBean(IamSecretProtector.class)
        @ConditionalOnBean(TransitProvider.class)
        IamSecretProtector transitSecretProtector(TransitProvider transit,
                                                  IamProperties properties) {
            return new TransitIamSecretProtector(transit, "iam-secrets");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AesSecretConfiguration {
        @Bean
        @ConditionalOnMissingBean(IamSecretProtector.class)
        IamSecretProtector aesSecretProtector(IamProperties properties) {
            String configured = properties.secret().key();
            byte[] key;
            if (configured != null && !configured.isBlank()) {
                key = Base64.getDecoder().decode(configured);
            } else {
                if (properties.store() == IamProperties.Store.JDBC) {
                    throw new IllegalStateException(
                            "seedj.iam.secret.key (or a TransitProvider) is required when "
                                    + "seedj.iam.store=jdbc; otherwise JWK/TOTP material cannot "
                                    + "survive a restart");
                }
                key = new byte[32];
                new SecureRandom().nextBytes(key);
                log.warn("seedj.iam.secret.key is not set; generated an ephemeral AES key. "
                        + "Set a stable key or enable Transit for restarts.");
            }
            return new AesGcmIamSecretProtector(key, properties.secret().keyName());
        }
    }

    /** Memory store: zero external dependencies, for tests and local runs. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "seedj.iam", name = "store", havingValue = "memory",
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
        @ConditionalOnProperty(prefix = "seedj.iam.jwk", name = "provider",
                havingValue = "memory", matchIfMissing = true)
        IamSigningKeyStore iamSigningKeyStore(IdGenerator<String> idGenerator) {
            return new InMemoryIamSigningKeyStore(idGenerator);
        }
    }
}
