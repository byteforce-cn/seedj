package cn.byteforce.seedj.starter.iam.security;

import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.id.IdGenerator;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds SAS clients from IAM properties and the explicitly enabled demo set. */
public final class IamClientFactory {

    public static final String DEFAULT_CLIENT_ID = "default-client";
    public static final String TLS_CLIENT_ID = "tls-client";
    public static final String NATIVE_CLIENT_ID = "native-app";
    public static final String TLS_CLIENT_SUBJECT_DN = "CN=tls-client";

    private static final org.springframework.security.crypto.password.PasswordEncoder DEMO_ENCODER =
            org.springframework.security.crypto.factory.PasswordEncoderFactories
                    .createDelegatingPasswordEncoder();

    private IamClientFactory() {
    }

    public static List<RegisteredClient> fromProperties(IamProperties properties,
                                                        IdGenerator<String> idGenerator) {
        List<RegisteredClient> clients = new ArrayList<>();
        for (IamProperties.Client client : properties.clients()) {
            clients.add(build(client, properties, idGenerator.id()));
        }
        if (properties.demo().enabled()) {
            clients.add(demoDefault(properties, idGenerator.id()));
            clients.add(demoTls(properties, idGenerator.id()));
            if (properties.nativeAuth().enabled()) {
                clients.add(demoNative(properties, idGenerator.id()));
            }
        }
        return clients;
    }

    private static RegisteredClient build(IamProperties.Client client, IamProperties properties,
                                          String id) {
        RegisteredClient.Builder builder = RegisteredClient.withId(id)
                .clientId(client.clientId())
                .clientName(client.clientId());
        if (client.clientSecret() != null && !client.clientSecret().isBlank()) {
            String secret = client.clientSecret();
            builder.clientSecret(secret.startsWith("{") ? secret : DEMO_ENCODER.encode(secret));
        }
        builder.clientAuthenticationMethod(method(client.authenticationMethod()));
        for (String grant : client.grantTypes()) {
            builder.authorizationGrantType(grant(grant));
        }
        client.redirectUris().forEach(builder::redirectUri);
        client.scopes().forEach(builder::scope);
        ClientSettings.Builder settings = ClientSettings.builder()
                .requireProofKey(client.requireProofKey())
                .requireAuthorizationConsent(client.requireConsent());
        if (client.x509SubjectDn() != null && !client.x509SubjectDn().isBlank()) {
            settings.x509CertificateSubjectDN(client.x509SubjectDn());
        }
        builder.clientSettings(settings.build());
        builder.tokenSettings(tokenSettings(properties));
        return builder.build();
    }

    private static RegisteredClient demoDefault(IamProperties properties, String id) {
        return RegisteredClient.withId(id)
                .clientId(DEFAULT_CLIENT_ID)
                .clientSecret(DEMO_ENCODER.encode("secret"))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("http://127.0.0.1:8080/login/oauth2/code/default-client")
                .postLogoutRedirectUri("http://127.0.0.1:8080/logout/callback")
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope("read")
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(true)
                        .requireProofKey(true)
                        .build())
                .tokenSettings(tokenSettings(properties))
                .build();
    }

    private static RegisteredClient demoTls(IamProperties properties, String id) {
        return RegisteredClient.withId(id)
                .clientId(TLS_CLIENT_ID)
                .clientAuthenticationMethod(ClientAuthenticationMethod.TLS_CLIENT_AUTH)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("http://127.0.0.1:8080/login/oauth2/code/tls-client")
                .postLogoutRedirectUri("http://127.0.0.1:8080/logout/callback")
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope("read")
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(true)
                        .requireProofKey(true)
                        .x509CertificateSubjectDN(TLS_CLIENT_SUBJECT_DN)
                        .build())
                .tokenSettings(tokenSettings(properties))
                .build();
    }

    private static RegisteredClient demoNative(IamProperties properties, String id) {
        return RegisteredClient.withId(id)
                .clientId(NATIVE_CLIENT_ID)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(NativeAuthGrantType.NATIVE_AUTH)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope("offline_access")
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .requireProofKey(false)
                        .build())
                .tokenSettings(tokenSettings(properties))
                .build();
    }

    private static ClientAuthenticationMethod method(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "none" -> ClientAuthenticationMethod.NONE;
            case "client_secret_post" -> ClientAuthenticationMethod.CLIENT_SECRET_POST;
            case "tls_client_auth" -> ClientAuthenticationMethod.TLS_CLIENT_AUTH;
            case "private_key_jwt" -> ClientAuthenticationMethod.PRIVATE_KEY_JWT;
            default -> ClientAuthenticationMethod.CLIENT_SECRET_BASIC;
        };
    }

    private static AuthorizationGrantType grant(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "authorization_code" -> AuthorizationGrantType.AUTHORIZATION_CODE;
            case "refresh_token" -> AuthorizationGrantType.REFRESH_TOKEN;
            case "client_credentials" -> AuthorizationGrantType.CLIENT_CREDENTIALS;
            case "urn:seedj:params:oauth:grant-type:native-auth", "native-auth" ->
                    NativeAuthGrantType.NATIVE_AUTH;
            default -> new AuthorizationGrantType(value);
        };
    }

    private static TokenSettings tokenSettings(IamProperties properties) {
        return TokenSettings.builder()
                .accessTokenTimeToLive(properties.token().accessTtl())
                .refreshTokenTimeToLive(properties.token().refreshTtl())
                .authorizationCodeTimeToLive(properties.token().authorizationCodeTtl())
                .reuseRefreshTokens(false)
                .build();
    }
}
