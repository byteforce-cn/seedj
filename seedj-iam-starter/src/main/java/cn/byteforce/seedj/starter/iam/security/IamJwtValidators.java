package cn.byteforce.seedj.starter.iam.security;

import cn.byteforce.seedj.core.security.AccessTokenRules;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;

/**
 * RFC 9068 access-token validator: {@code typ=at+jwt}, issuer, timestamps, and a non-empty
 * audience. Using this instead of {@code createDefaultWithIssuer} is what lets at+jwt tokens
 * through while still rejecting ID tokens (typ=JWT).
 *
 * <p>规则本体在 {@link AccessTokenRules}（core，零 Spring）；本类只是把它接到 Spring Security 的 {@code
 * OAuth2TokenValidator} 上。资源服务器侧（biz）使用 同一份规则，保证"认证服务器放行的令牌资源服务器一定放行"。
 */
public final class IamJwtValidators {

    private IamJwtValidators() {}

    /** Spring 适配器：类型、issuer、时间、受众四条校验。 */
    public static OAuth2TokenValidator<Jwt> accessToken(String issuer) {
        return new DelegatingOAuth2TokenValidator<>(
                new JwtTypeValidator(AccessTokenRules.ACCESS_TOKEN_TYPE),
                new JwtIssuerValidator(issuer),
                new JwtTimestampValidator(),
                new JwtClaimValidator<>("aud", AccessTokenRules::hasAudience));
    }

    /** 非空受众判定；委托给 {@link AccessTokenRules}。 */
    public static boolean hasAudience(Object value) {
        return AccessTokenRules.hasAudience(value);
    }
}
