package cn.byteforce.seedj.iam.autoconfigure;

import cn.byteforce.seedj.iam.config.IamProperties;
import cn.byteforce.seedj.iam.identity.IamAuthorizationService;
import cn.byteforce.seedj.iam.identity.IamIdentityService;
import cn.byteforce.seedj.iam.identity.IamMfaChallengeService;
import cn.byteforce.seedj.iam.identity.IamTotpService;
import cn.byteforce.seedj.iam.nativeauth.IamLoginRateLimiter;
import cn.byteforce.seedj.iam.nativeauth.NativeAuthAuthenticationConverter;
import cn.byteforce.seedj.iam.nativeauth.NativeAuthAuthenticationProvider;
import cn.byteforce.seedj.core.security.SecurityAuditSink;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.id.IdGenerator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

import java.time.Clock;
import java.time.Duration;

/** native-auth is off by default because it is a non-standard first-party grant. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "seedj.iam.native-auth", name = "enabled", havingValue = "true")
public class IamNativeAuthConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public NativeAuthAuthenticationConverter nativeAuthAuthenticationConverter() {
        return new NativeAuthAuthenticationConverter();
    }

    @Bean
    @ConditionalOnMissingBean
    public IamLoginRateLimiter iamLoginRateLimiter(Clock clock, IamProperties properties) {
        return new IamLoginRateLimiter(properties.nativeAuth().maxAttempts(),
                Duration.ofMinutes(5), clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public NativeAuthAuthenticationProvider nativeAuthAuthenticationProvider(
            IamIdentityService identities, IamUserRepository users,
            IamAuthorizationService authorization,
            ObjectProvider<IamTotpService> totp,
            IamMfaChallengeService challenges,
            OAuth2AuthorizationService authorizations,
            OAuth2TokenGenerator<?> tokenGenerator,
            IamProperties properties, IamLoginRateLimiter rateLimiter, SecurityAuditSink audit,
            IdGenerator<String> idGenerator) {
        return new NativeAuthAuthenticationProvider(identities, users, authorization,
                totp.getIfAvailable(), challenges, authorizations, tokenGenerator,
                properties, rateLimiter, audit, idGenerator);
    }
}
