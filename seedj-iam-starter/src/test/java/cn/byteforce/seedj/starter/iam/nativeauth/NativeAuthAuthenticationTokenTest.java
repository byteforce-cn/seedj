package cn.byteforce.seedj.starter.iam.nativeauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** native-auth 请求/授权令牌载体。 */
class NativeAuthAuthenticationTokenTest {

    @Test
    @DisplayName("有用户名时 principal 为用户名，否则回落到 mfa_token")
    void principalPrefersUsername() {
        NativeAuthAuthenticationToken password = new NativeAuthAuthenticationToken(null, null,
                "alice", "secret", null, null, "device-1", "Pixel", "1.0", Set.of("openid"));
        NativeAuthAuthenticationToken mfa = new NativeAuthAuthenticationToken(null, null, null,
                null, "mfa-token-1", "123456", "device-1", "Pixel", "1.0", Set.of());

        assertThat(password.getPrincipal()).isEqualTo("alice");
        assertThat(password.getCredentials()).isEqualTo("secret");
        assertThat(mfa.getPrincipal()).isEqualTo("mfa-token-1");
        assertThat(mfa.getCredentials()).isNull();
    }

    @Test
    @DisplayName("未认证、scopes 为空安全地归一为空集合")
    void scopesDefaultToEmptyAndTokenIsNotAuthenticated() {
        NativeAuthAuthenticationToken token = new NativeAuthAuthenticationToken(null, null,
                "alice", null, null, null, null, null, null, null);

        assertThat(token.scopes()).isEmpty();
        assertThat(token.isAuthenticated()).isFalse();
        assertThat(token.registeredClient()).isNull();
        assertThat(token.clientPrincipal()).isNull();
        assertThat(token.deviceId()).isNull();
        assertThat(token.deviceName()).isNull();
        assertThat(token.appVersion()).isNull();
        assertThat(token.otp()).isNull();
        assertThat(token.mfaToken()).isNull();
        assertThat(token.password()).isNull();
    }

    @Test
    @DisplayName("保留调用方传入的客户端与 scope 快照")
    void keepsClientAndScopeSnapshot() {
        RegisteredClient client = RegisteredClient.withId("client-1").clientId("native-app")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .build();
        Set<String> scopes = Set.of("openid", "profile");

        NativeAuthAuthenticationToken token = new NativeAuthAuthenticationToken(null, client,
                "alice", null, null, null, null, null, null, scopes);

        assertThat(token.registeredClient()).isSameAs(client);
        assertThat(token.scopes()).isEqualTo(scopes);
    }
}
