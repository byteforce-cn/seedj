package cn.byteforce.seedj.starter.iam.security;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.iam.spi.IamPasswordHasher;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.starter.iam.IamProperties;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

class IamClientFactoryTest {

    private final AtomicLong seq = new AtomicLong();
    private final IdGenerator<String> idGenerator = () -> "client-" + seq.incrementAndGet();

    @Test
    void buildsConfiguredClientWithAuthenticationGrantAndRedirectSettings() {
        IamProperties.Client configuration =
                new IamProperties.Client(
                        "web",
                        "web-secret",
                        List.of("https://app.example.com/callback"),
                        List.of("openid", "read"),
                        List.of(
                                "authorization_code",
                                "refresh_token",
                                "client_credentials",
                                "native-auth",
                                "urn:ietf:params:oauth:grant-type:device_code"),
                        false,
                        false,
                        "private_key_jwt",
                        "CN=web-client");

        List<RegisteredClient> clients =
                IamClientFactory.fromProperties(
                        properties(new IamProperties.Demo(false), List.of(configuration)),
                        idGenerator);

        assertThat(clients).hasSize(1);
        RegisteredClient client = clients.get(0);
        assertThat(client.getClientId()).isEqualTo("web");
        assertThat(client.getClientSecret()).startsWith("{bcrypt}");
        assertThat(client.getClientAuthenticationMethods())
                .containsExactly(ClientAuthenticationMethod.PRIVATE_KEY_JWT);
        assertThat(client.getAuthorizationGrantTypes())
                .containsExactlyInAnyOrder(
                        AuthorizationGrantType.AUTHORIZATION_CODE,
                        AuthorizationGrantType.REFRESH_TOKEN,
                        AuthorizationGrantType.CLIENT_CREDENTIALS,
                        NativeAuthGrantType.NATIVE_AUTH,
                        new AuthorizationGrantType("urn:ietf:params:oauth:grant-type:device_code"));
        assertThat(client.getRedirectUris()).containsExactly("https://app.example.com/callback");
        assertThat(client.getScopes()).containsExactlyInAnyOrder("openid", "read");
        assertThat(client.getClientSettings().isRequireProofKey()).isFalse();
        assertThat(client.getClientSettings().isRequireAuthorizationConsent()).isFalse();
        assertThat(client.getClientSettings().getX509CertificateSubjectDN())
                .isEqualTo("CN=web-client");
        assertThat(client.getTokenSettings().getAccessTokenTimeToLive())
                .isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void keepsPreEncodedSecretsAndSupportsEveryAuthenticationMethod() {
        List<RegisteredClient> clients =
                IamClientFactory.fromProperties(
                        properties(
                                new IamProperties.Demo(false),
                                List.of(
                                        client(
                                                "encoded",
                                                "{bcrypt}hash",
                                                "none",
                                                "urn:seedj:params:oauth:grant-type:native-auth"),
                                        client(
                                                "post",
                                                "secret",
                                                "client_secret_post",
                                                "refresh_token"),
                                        client(
                                                "tls",
                                                null,
                                                "tls_client_auth",
                                                "authorization_code"),
                                        client(
                                                "basic",
                                                null,
                                                "client_secret_basic",
                                                "client_credentials"))),
                        idGenerator);

        assertThat(clients.get(0).getClientSecret()).isEqualTo("{bcrypt}hash");
        assertThat(clients.get(0).getClientAuthenticationMethods())
                .containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(clients.get(1).getClientAuthenticationMethods())
                .containsExactly(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        assertThat(clients.get(2).getClientAuthenticationMethods())
                .containsExactly(ClientAuthenticationMethod.TLS_CLIENT_AUTH);
        assertThat(clients.get(2).getClientSecret()).isNull();
        assertThat(clients.get(3).getClientAuthenticationMethods())
                .containsExactly(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
    }

    @Test
    void addsDemoClientsIncludingNativeAppWhenNativeAuthIsEnabled() {
        List<RegisteredClient> clients =
                IamClientFactory.fromProperties(
                        properties(
                                new IamProperties.Demo(true),
                                List.of(),
                                new IamProperties.NativeAuth(true, null, null, null)),
                        idGenerator);

        assertThat(clients)
                .extracting(RegisteredClient::getClientId)
                .containsExactly(
                        IamClientFactory.DEFAULT_CLIENT_ID,
                        IamClientFactory.TLS_CLIENT_ID,
                        IamClientFactory.NATIVE_CLIENT_ID);
        RegisteredClient tls = clients.get(1);
        assertThat(tls.getClientSettings().getX509CertificateSubjectDN())
                .isEqualTo(IamClientFactory.TLS_CLIENT_SUBJECT_DN);
        RegisteredClient nativeApp = clients.get(2);
        assertThat(nativeApp.getClientAuthenticationMethods())
                .containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(nativeApp.getAuthorizationGrantTypes())
                .contains(NativeAuthGrantType.NATIVE_AUTH);
    }

    @Test
    void omitsNativeAppWhenNativeAuthIsDisabled() {
        List<RegisteredClient> clients =
                IamClientFactory.fromProperties(
                        properties(new IamProperties.Demo(true), List.of()), idGenerator);

        assertThat(clients)
                .extracting(RegisteredClient::getClientId)
                .containsExactly(
                        IamClientFactory.DEFAULT_CLIENT_ID, IamClientFactory.TLS_CLIENT_ID);
    }

    private static IamProperties properties(
            IamProperties.Demo demo, List<IamProperties.Client> clients) {
        return properties(demo, clients, new IamProperties.NativeAuth(false, null, null, null));
    }

    private static IamProperties properties(
            IamProperties.Demo demo,
            List<IamProperties.Client> clients,
            IamProperties.NativeAuth nativeAuth) {
        return new IamProperties(
                null,
                null,
                null,
                null,
                null,
                new IamProperties.Token(Duration.ofMinutes(5), null, null, true, null),
                null,
                nativeAuth,
                null,
                null,
                null,
                demo,
                null,
                null,
                clients);
    }

    private static IamProperties.Client client(
            String clientId, String secret, String method, String grant) {
        return new IamProperties.Client(
                clientId,
                secret,
                List.of("https://app.example.com/cb"),
                List.of("openid"),
                List.of(grant),
                null,
                null,
                method,
                null);
    }

    @Test
    void demoSecretsAreHashedWithTheDelegatingEncoder() {
        IamPasswordHasher hasher =
                new cn.byteforce.seedj.starter.iam.identity.IamPasswordHasherAdapter(
                        org.springframework.security.crypto.factory.PasswordEncoderFactories
                                .createDelegatingPasswordEncoder());
        RegisteredClient demo =
                IamClientFactory.fromProperties(
                                properties(new IamProperties.Demo(true), List.of()), idGenerator)
                        .get(0);

        assertThat(hasher.matches("secret", demo.getClientSecret())).isTrue();
    }
}
