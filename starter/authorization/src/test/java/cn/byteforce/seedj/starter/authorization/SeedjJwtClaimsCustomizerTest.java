package cn.byteforce.seedj.starter.authorization;

import cn.byteforce.seedj.security.core.CustomUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SeedjJwtClaimsCustomizer} 契约测试：CustomUserDetails 主体注入、
 * 其他主体与令牌类型的行为边界。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class SeedjJwtClaimsCustomizerTest {

    private final SeedjJwtClaimsCustomizer customizer =
            new SeedjJwtClaimsCustomizer(new AuthorizationServerProperties());

    @Test
    void extractsIdentityClaimsFromCustomUserDetails() {
        Authentication authentication = authentication(new CustomUserDetails(
                42L, "alice", "pwd", null, null, 7L, null,
                AuthorityUtils.NO_AUTHORITIES));

        Map<String, Object> claims = customizer.claimsFor(authentication);

        assertThat(claims)
                .containsEntry("userId", 42L)
                .containsEntry("tenantId", 7L)
                .containsEntry("preferred_username", "alice");
    }

    @Test
    void skipsNullIdentityValues() {
        Authentication authentication = authentication(new CustomUserDetails(
                null, "bob", "pwd", null, null, null, null,
                AuthorityUtils.NO_AUTHORITIES));

        Map<String, Object> claims = customizer.claimsFor(authentication);

        assertThat(claims).containsOnlyKeys("preferred_username");
    }

    @Test
    void extractsAuthoritiesClaimFromCustomUserDetails() {
        Authentication authentication = authentication(new CustomUserDetails(
                42L, "alice", "pwd", null, null, 7L, null,
                AuthorityUtils.createAuthorityList("identity:manage", "hr:manage")));

        Map<String, Object> claims = customizer.claimsFor(authentication);

        assertThat(claims)
                .containsEntry("authorities", List.of("identity:manage", "hr:manage"))
                .containsEntry("userId", 42L);
    }

    @Test
    void authoritiesClaimIsAbsentWhenPrincipalHasNoAuthorities() {
        Authentication authentication = authentication(new CustomUserDetails(
                42L, "alice", "pwd", null, null, 7L, null,
                AuthorityUtils.NO_AUTHORITIES));

        assertThat(customizer.claimsFor(authentication)).doesNotContainKey("authorities");
    }

    @Test
    void authoritiesClaimNameIsConfigurable() {
        AuthorizationServerProperties properties = new AuthorizationServerProperties();
        properties.getClaims().setAuthorities("perms");
        SeedjJwtClaimsCustomizer configurable = new SeedjJwtClaimsCustomizer(properties);
        Authentication authentication = authentication(new CustomUserDetails(
                42L, "alice", "pwd", null, null, 7L, null,
                AuthorityUtils.createAuthorityList("identity:manage")));

        Map<String, Object> claims = configurable.claimsFor(authentication);

        assertThat(claims)
                .containsEntry("perms", List.of("identity:manage"))
                .doesNotContainKey("authorities");
    }

    @Test
    void returnsEmptyForOtherPrincipals() {
        Authentication authentication = new UsernamePasswordAuthenticationToken("plain", "pwd");

        assertThat(customizer.claimsFor(authentication)).isEmpty();
        assertThat(customizer.claimsFor(null)).isEmpty();
    }

    @Test
    void customizeAppliesClaimsForAccessToken() {
        JwtEncodingContext context = mock(JwtEncodingContext.class);
        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder();
        when(context.getTokenType()).thenReturn(OAuth2TokenType.ACCESS_TOKEN);
        when(context.getPrincipal()).thenReturn(authentication(new CustomUserDetails(
                42L, "alice", "pwd", null, null, 7L, null,
                AuthorityUtils.NO_AUTHORITIES)));
        when(context.getClaims()).thenReturn(claimsBuilder);

        customizer.customize(context);

        Map<String, Object> claims = claimsBuilder.build().getClaims();
        assertThat(claims)
                .containsEntry("userId", 42L)
                .containsEntry("tenantId", 7L)
                .containsEntry("preferred_username", "alice");
    }

    @Test
    void customizeSkipsNonAccessTokens() {
        JwtEncodingContext context = mock(JwtEncodingContext.class);
        when(context.getTokenType()).thenReturn(new OAuth2TokenType("id_token"));

        customizer.customize(context);

        verify(context, never()).getClaims();
    }

    private static Authentication authentication(CustomUserDetails principal) {
        return new UsernamePasswordAuthenticationToken(principal, principal.getPassword(),
                principal.getAuthorities());
    }
}
