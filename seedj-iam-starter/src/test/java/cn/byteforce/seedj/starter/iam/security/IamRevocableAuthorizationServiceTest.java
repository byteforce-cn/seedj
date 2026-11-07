package cn.byteforce.seedj.starter.iam.security;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.infra.local.iam.InMemoryIamTokenRevoker;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

class IamRevocableAuthorizationServiceTest {

    private final InMemoryOAuth2AuthorizationService delegate =
            new InMemoryOAuth2AuthorizationService();
    private final InMemoryIamTokenRevoker revoker = new InMemoryIamTokenRevoker();
    private final IamRevocableAuthorizationService service =
            new IamRevocableAuthorizationService(delegate, revoker);
    private final OAuth2Authorization authorization = authorization("alice");

    @Test
    void hidesAuthorizationsOfRevokedPrincipals() {
        service.save(authorization);
        assertThat(service.findById("a-1")).isNotNull();
        assertThat(service.findByToken("access-token", OAuth2TokenType.ACCESS_TOKEN)).isNotNull();

        revoker.revokeAllForUser("default", "alice");

        assertThat(service.findById("a-1")).isNull();
        assertThat(service.findByToken("access-token", OAuth2TokenType.ACCESS_TOKEN)).isNull();
        assertThat(delegate.findById("a-1")).isNotNull();
    }

    @Test
    void removesThroughTheDelegate() {
        service.save(authorization);

        service.remove(authorization);

        assertThat(delegate.findById("a-1")).isNull();
        assertThat(service.findById("a-1")).isNull();
    }

    @Test
    void passesAuthorizationsThroughWithoutRevoker() {
        IamRevocableAuthorizationService withoutRevoker =
                new IamRevocableAuthorizationService(delegate, null);
        delegate.save(authorization);

        assertThat(withoutRevoker.findById("a-1")).isNotNull();
        assertThat(withoutRevoker.findByToken("access-token", OAuth2TokenType.ACCESS_TOKEN))
                .isNotNull();
        assertThat(withoutRevoker.findById("missing")).isNull();
    }

    private static OAuth2Authorization authorization(String principal) {
        OAuth2AccessToken accessToken =
                new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        "access-token",
                        Instant.now(),
                        Instant.now().plusSeconds(300));
        return OAuth2Authorization.withRegisteredClient(client())
                .id("a-1")
                .principalName(principal)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .token(accessToken)
                .build();
    }

    private static RegisteredClient client() {
        return RegisteredClient.withId("client-1")
                .clientId("web")
                .clientSecret("{noop}secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://127.0.0.1:8080/login/oauth2/code/web")
                .build();
    }
}
