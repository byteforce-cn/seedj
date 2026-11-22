package cn.byteforce.seedj.starter.iam.autoconfigure;

import cn.byteforce.seedj.core.security.SecurityAuditSink;
import cn.byteforce.seedj.iam.IamEnabledMarker;
import cn.byteforce.seedj.iam.engine.identity.IamIdentityService;
import cn.byteforce.seedj.iam.engine.identity.IamMfaChallengeService;
import cn.byteforce.seedj.iam.engine.identity.IamTotpService;
import cn.byteforce.seedj.iam.spi.IamAuthorizationRepository;
import cn.byteforce.seedj.iam.spi.IamGroupRepository;
import cn.byteforce.seedj.iam.spi.IamPasswordHasher;
import cn.byteforce.seedj.iam.spi.IamSecretProtector;
import cn.byteforce.seedj.iam.spi.IamTokenRevoker;
import cn.byteforce.seedj.iam.spi.IamTotpRepository;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.starter.iam.identity.IamBootstrapRunner;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * IAM 服务图：只消费 SPI，不依赖任何适配器。
 *
 * <p>存储、密钥保护器与授权绑定由适配器自带自动配置提供（{@code seedj-infra-local} / {@code
 * seedj-jdbc-postgresql}）。本类保留与存储选型无关的纯服务装配：密码哈希、身份/授权/TOTP 服务、 引导管理员与限流器。
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
public class IamStoreConfiguration {

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
    public IamIdentityService iamIdentityService(
            IamUserRepository users,
            IamPasswordHasher hasher,
            IamTokenRevoker revoker,
            SecurityAuditSink audit,
            IdGenerator<String> idGenerator) {
        return new IamIdentityService(users, hasher, revoker, audit, idGenerator);
    }

    @Bean
    @ConditionalOnMissingBean
    public cn.byteforce.seedj.iam.engine.identity.IamAuthorizationService iamAuthorizationService(
            IamAuthorizationRepository authorization,
            IamGroupRepository groups,
            IamProperties properties) {
        return new cn.byteforce.seedj.iam.engine.identity.IamAuthorizationService(
                authorization, groups, properties.rbac().cacheTtl());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "seedj.iam.mfa.totp",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public IamTotpService iamTotpService(
            IamTotpRepository totp,
            IamUserRepository users,
            IamSecretProtector protector,
            IamPasswordHasher hasher,
            IamProperties properties,
            SecurityAuditSink audit,
            Clock clock,
            IdGenerator<String> idGenerator) {
        IamProperties.Totp totpPolicy = properties.mfa().totp();
        return new IamTotpService(
                totp,
                users,
                protector,
                hasher,
                new IamTotpService.Policy(
                        totpPolicy.issuer(),
                        totpPolicy.digits(),
                        totpPolicy.period(),
                        totpPolicy.window(),
                        totpPolicy.recoveryCodes()),
                audit,
                clock,
                idGenerator);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "seedj.iam.native-auth", name = "enabled", havingValue = "true")
    public IamMfaChallengeService iamMfaChallengeService(
            IamTotpRepository totp,
            IamProperties properties,
            Clock clock,
            IdGenerator<String> idGenerator) {
        IamProperties.NativeAuth nativeAuth = properties.nativeAuth();
        return new IamMfaChallengeService(
                totp,
                new IamMfaChallengeService.Policy(
                        nativeAuth.mfaTokenTtl(), nativeAuth.maxAttempts()),
                clock,
                idGenerator);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "seedj.iam.mfa.totp",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public cn.byteforce.seedj.iam.engine.identity.IamMfaAttemptLimiter iamMfaAttemptLimiter(
            Clock clock) {
        return new cn.byteforce.seedj.iam.engine.identity.IamMfaAttemptLimiter(
                5, java.time.Duration.ofMinutes(5), clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public IamBootstrapRunner iamBootstrapRunner(
            IamProperties properties,
            IamUserRepository users,
            IamAuthorizationRepository authorization,
            IamIdentityService identities,
            IdGenerator<String> idGenerator) {
        return new IamBootstrapRunner(
                properties.admin(), users, authorization, identities, idGenerator);
    }
}
