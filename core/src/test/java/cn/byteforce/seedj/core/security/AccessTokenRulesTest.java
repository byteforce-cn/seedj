package cn.byteforce.seedj.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccessTokenRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
    private static final String ISSUER = "https://iam.example.com";

    private static TokenClaims token(
            String type, String issuer, Instant expiresAt, List<String> audience) {
        return new TokenClaims("alice", type, issuer, expiresAt, audience, Map.of());
    }

    private static TokenClaims valid() {
        return token(
                AccessTokenRules.ACCESS_TOKEN_TYPE,
                ISSUER,
                NOW.plus(Duration.ofMinutes(15)),
                List.of("order-api"));
    }

    @Test
    @DisplayName("at+jwt + issuer + 未过期 + 非空 aud：通过")
    void acceptsRfc9068AccessToken() {
        assertThat(AccessTokenRules.check(valid(), ISSUER, NOW)).isEmpty();
    }

    @Test
    @DisplayName("ID Token（typ=JWT）不能当作访问令牌")
    void rejectsIdToken() {
        TokenClaims idToken =
                token("JWT", ISSUER, NOW.plus(Duration.ofMinutes(15)), List.of("order-api"));
        assertThat(AccessTokenRules.check(idToken, ISSUER, NOW))
                .contains(SecurityErrorCode.AUTHENTICATION_FAILED);
    }

    @Test
    @DisplayName("typ 缺失视为无效")
    void rejectsMissingType() {
        assertThat(
                        AccessTokenRules.check(
                                token("", ISSUER, NOW.plusSeconds(600), List.of("order-api")),
                                ISSUER,
                                NOW))
                .contains(SecurityErrorCode.AUTHENTICATION_FAILED);
    }

    @Test
    @DisplayName("issuer 不匹配时拒绝")
    void rejectsForeignIssuer() {
        assertThat(AccessTokenRules.check(valid(), "https://other.example.com", NOW))
                .contains(SecurityErrorCode.AUTHENTICATION_FAILED);
    }

    @Test
    @DisplayName("缺少 exp 时拒绝（访问令牌必须有有效期）")
    void rejectsMissingExpiry() {
        assertThat(
                        AccessTokenRules.check(
                                token(
                                        AccessTokenRules.ACCESS_TOKEN_TYPE,
                                        ISSUER,
                                        null,
                                        List.of("order-api")),
                                ISSUER,
                                NOW))
                .contains(SecurityErrorCode.AUTHENTICATION_FAILED);
    }

    @Test
    @DisplayName("超过有效期 + 时钟偏移后返回 TOKEN_EXPIRED")
    void rejectsExpiredToken() {
        Instant expired = NOW.minus(AccessTokenRules.DEFAULT_CLOCK_SKEW).minusSeconds(1);
        assertThat(
                        AccessTokenRules.check(
                                token(
                                        AccessTokenRules.ACCESS_TOKEN_TYPE,
                                        ISSUER,
                                        expired,
                                        List.of("order-api")),
                                ISSUER,
                                NOW))
                .contains(SecurityErrorCode.TOKEN_EXPIRED);
    }

    @Test
    @DisplayName("刚过期但仍在时钟偏移内：通过")
    void toleratesClockSkew() {
        Instant justExpired = NOW.minus(AccessTokenRules.DEFAULT_CLOCK_SKEW).plusSeconds(1);
        assertThat(
                        AccessTokenRules.check(
                                token(
                                        AccessTokenRules.ACCESS_TOKEN_TYPE,
                                        ISSUER,
                                        justExpired,
                                        List.of("order-api")),
                                ISSUER,
                                NOW))
                .isEmpty();
    }

    @Test
    @DisplayName("aud 为空时拒绝")
    void rejectsEmptyAudience() {
        assertThat(
                        AccessTokenRules.check(
                                token(
                                        AccessTokenRules.ACCESS_TOKEN_TYPE,
                                        ISSUER,
                                        NOW.plusSeconds(600),
                                        List.of()),
                                ISSUER,
                                NOW))
                .contains(SecurityErrorCode.AUTHENTICATION_FAILED);
    }

    @Test
    @DisplayName("空 issuer 是调用方配置错误，抛 IllegalArgumentException")
    void blankExpectedIssuerIsProgrammingError() {
        assertThatThrownBy(() -> AccessTokenRules.check(valid(), " ", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null 声明视为未认证")
    void nullClaimsMeansAnonymous() {
        assertThat(AccessTokenRules.check(null, ISSUER, NOW))
                .contains(SecurityErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Test
    @DisplayName("权限串按 SCOPE/ROLE/PERM/GROUP 顺序生成")
    void buildsPrefixedAuthorities() {
        TokenClaims claims =
                new TokenClaims(
                        "alice",
                        "at+jwt",
                        ISSUER,
                        NOW.plusSeconds(600),
                        List.of("order-api"),
                        Map.of(
                                "scope",
                                List.of("read"),
                                "roles",
                                List.of("ADMIN", "OPS"),
                                "permissions",
                                List.of("order:read"),
                                "groups",
                                List.of("sales")));

        assertThat(AccessTokenRules.authorities(claims))
                .containsExactly(
                        "SCOPE_read", "ROLE_ADMIN", "ROLE_OPS", "PERM_order:read", "GROUP_sales");
    }

    @Test
    @DisplayName("空格分隔的 scope 字符串也能正确拆分")
    void splitsSpaceDelimitedScope() {
        TokenClaims claims =
                new TokenClaims(
                        "alice",
                        "at+jwt",
                        ISSUER,
                        NOW.plusSeconds(600),
                        List.of("order-api"),
                        Map.of("scope", "openid read write"));

        assertThat(AccessTokenRules.authorities(claims))
                .containsExactly("SCOPE_openid", "SCOPE_read", "SCOPE_write");
    }

    @Test
    @DisplayName("user_id 优先于 sub 作为主体标识")
    void prefersUserIdClaim() {
        TokenClaims claims =
                new TokenClaims(
                        "alice",
                        "at+jwt",
                        ISSUER,
                        NOW.plusSeconds(600),
                        List.of("order-api"),
                        Map.of("user_id", "u-1", "roles", List.of("ADMIN")));

        Caller caller = AccessTokenRules.toCaller(claims, "t-1");

        assertThat(caller.id()).isEqualTo("u-1");
        assertThat(caller.username()).isEqualTo("alice");
        assertThat(caller.tenantId()).isEqualTo("t-1");
        assertThat(caller.hasRole("ADMIN")).isTrue();
    }

    @Test
    @DisplayName("缺少 user_id 时回落 sub")
    void fallsBackToSubject() {
        Caller caller = AccessTokenRules.toCaller(valid(), null);
        assertThat(caller.id()).isEqualTo("alice");
        assertThat(caller.tenantId()).isEqualTo(Caller.DEFAULT_TENANT);
    }

    @Test
    @DisplayName("amr/acr 缺失时使用默认值")
    void appliesDefaultAmrAndAcr() {
        Caller caller = AccessTokenRules.toCaller(valid(), "t-1");
        assertThat(caller.amr()).containsExactly(Caller.DEFAULT_AMR);
        assertThat(caller.acr()).isEqualTo(Caller.DEFAULT_ACR);
    }

    @Test
    @DisplayName("hasAudience 兼容字符串与集合")
    void audiencePredicate() {
        assertThat(AccessTokenRules.hasAudience("order-api")).isTrue();
        assertThat(AccessTokenRules.hasAudience(List.of("order-api"))).isTrue();
        assertThat(AccessTokenRules.hasAudience("")).isFalse();
        assertThat(AccessTokenRules.hasAudience(List.of())).isFalse();
        assertThat(AccessTokenRules.hasAudience(null)).isFalse();
    }
}
