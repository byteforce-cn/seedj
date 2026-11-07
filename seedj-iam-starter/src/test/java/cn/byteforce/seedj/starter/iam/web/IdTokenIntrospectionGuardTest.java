package cn.byteforce.seedj.starter.iam.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenIntrospection;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenIntrospectionAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/** RFC 7662：ID Token 不得被内省为 active=true。 */
class IdTokenIntrospectionGuardTest {

    private static final String ID_TOKEN = "id-token-value";
    private static final String ACCESS_TOKEN = "access-token-value";
    private static final Authentication PRINCIPAL =
            new UsernamePasswordAuthenticationToken("web", "secret");

    private final OAuth2AuthorizationService authorizations =
            mock(OAuth2AuthorizationService.class);
    private IdTokenIntrospectionGuard guard;

    @BeforeEach
    void setUp() {
        guard = new IdTokenIntrospectionGuard(authorizations);
    }

    @Test
    void supportsOnlyIntrospectionRequests() {
        assertThat(guard.supports(OAuth2TokenIntrospectionAuthenticationToken.class)).isTrue();
        assertThat(guard.supports(UsernamePasswordAuthenticationToken.class)).isFalse();
    }

    @Test
    void ignoresOtherAuthenticationTypes() {
        assertThat(guard.authenticate(new UsernamePasswordAuthenticationToken("a", "b"))).isNull();
    }

    @Test
    void ignoresUnknownToken() {
        when(authorizations.findByToken("unknown", null)).thenReturn(null);

        assertThat(guard.authenticate(introspection("unknown"))).isNull();
    }

    @Test
    void ignoresAccessToken() {
        when(authorizations.findByToken(ACCESS_TOKEN, null)).thenReturn(accessTokenAuthorization());

        assertThat(guard.authenticate(introspection(ACCESS_TOKEN))).isNull();
    }

    @Test
    void ignoresTokenThatIsNotTheIdTokenOfTheAuthorization() {
        when(authorizations.findByToken(ACCESS_TOKEN, null)).thenReturn(idTokenAuthorization());

        assertThat(guard.authenticate(introspection(ACCESS_TOKEN))).isNull();
    }

    @Test
    void reportsIdTokenAsInactive() {
        when(authorizations.findByToken(ID_TOKEN, null)).thenReturn(idTokenAuthorization());

        Authentication result = guard.authenticate(introspection(ID_TOKEN));

        assertThat(result).isInstanceOf(OAuth2TokenIntrospectionAuthenticationToken.class);
        OAuth2TokenIntrospection claims =
                ((OAuth2TokenIntrospectionAuthenticationToken) result).getTokenClaims();
        assertThat(claims.isActive()).isFalse();
    }

    private static OAuth2TokenIntrospectionAuthenticationToken introspection(String token) {
        return new OAuth2TokenIntrospectionAuthenticationToken(
                token, PRINCIPAL, "access_token", Map.of());
    }

    private static OAuth2Authorization idTokenAuthorization() {
        Instant now = Instant.now();
        return OAuth2Authorization.withRegisteredClient(client())
                .principalName("alice")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .token(new OidcIdToken(ID_TOKEN, now, now.plusSeconds(300), Map.of("sub", "alice")))
                .build();
    }

    private static OAuth2Authorization accessTokenAuthorization() {
        Instant now = Instant.now();
        return OAuth2Authorization.withRegisteredClient(client())
                .principalName("alice")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .accessToken(
                        new OAuth2AccessToken(
                                OAuth2AccessToken.TokenType.BEARER,
                                ACCESS_TOKEN,
                                now,
                                now.plusSeconds(300),
                                Set.of("openid")))
                .build();
    }

    private static RegisteredClient client() {
        return RegisteredClient.withId("client-1")
                .clientId("web")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://app.example.com/cb")
                .scope("openid")
                .build();
    }
}
