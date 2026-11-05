package cn.byteforce.seedj.iam.nativeauth;

import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.annotation.EnableIamServer;
import cn.byteforce.seedj.iam.autoconfigure.IamAutoConfiguration;
import cn.byteforce.seedj.iam.identity.IamIdentityService;
import cn.byteforce.seedj.iam.identity.IamTotpService;
import cn.byteforce.seedj.iam.totp.Base32;
import cn.byteforce.seedj.iam.totp.TotpAlgorithm;
import cn.byteforce.seedj.iam.totp.TotpGenerator;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.iam.TestIdGenerators;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.context.annotation.Bean;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeAuthFlowTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(TestApplication.class)
            .withConfiguration(AutoConfigurations.of(IamAutoConfiguration.class))
            .withPropertyValues(
                    "seedj.iam.issuer=https://iam.test",
                    "seedj.iam.store=memory",
                    "seedj.iam.demo.enabled=true",
                    "seedj.iam.native-auth.enabled=true",
                    "seedj.iam.mfa.totp.enabled=true",
                    "seedj.iam.secret.key=" + java.util.Base64.getEncoder().encodeToString(new byte[32]),
                    "seedj.pki.enabled=false",
                    "seedj.transit.enabled=false",
                    "seedj.eventbus.enabled=false",
                    "seedj.id.enabled=false",
                    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration");

    @Test
    void passwordGrantIssuesTokensAndSupportsTwoPhaseMfa() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            IamIdentityService identities = context.getBean(IamIdentityService.class);
            IamUser user = identities.createUser("default", "alice", "alice-password",
                    "Alice", "alice@example.com");
            identities.createUser("default", "locked", "locked-password", "Locked", null);

            RegisteredClientRepository clients = context.getBean(RegisteredClientRepository.class);
            RegisteredClient client = clients.findByClientId("native-app");
            OAuth2ClientAuthenticationToken clientPrincipal = new OAuth2ClientAuthenticationToken(
                    client, ClientAuthenticationMethod.NONE, "native-app");
            NativeAuthAuthenticationProvider provider =
                    context.getBean(NativeAuthAuthenticationProvider.class);
            AuthorizationServerSettings settings = context.getBean(AuthorizationServerSettings.class);
            AuthorizationServerContextHolder.setContext(new AuthorizationServerContext() {
                @Override
                public String getIssuer() {
                    return settings.getIssuer();
                }

                @Override
                public AuthorizationServerSettings getAuthorizationServerSettings() {
                    return settings;
                }
            });

            assertThatThrownBy(() -> provider.authenticate(new NativeAuthAuthenticationToken(
                    clientPrincipal, client, "alice", "wrong-password", null, null,
                    "device-1", "Pixel", "1.0", Set.of("openid"))))
                    .isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                            exception -> assertThat(exception.getError().getErrorCode())
                                    .isEqualTo("invalid_grant"));
            assertThatThrownBy(() -> provider.authenticate(new NativeAuthAuthenticationToken(
                    clientPrincipal, client, "nobody", "whatever", null, null,
                    "device-1", "Pixel", "1.0", Set.of("openid"))))
                    .isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                            exception -> assertThat(exception.getError().getErrorCode())
                                    .isEqualTo("invalid_grant"));
            for (int attempt = 0; attempt < 5; attempt++) {
                assertThatThrownBy(() -> provider.authenticate(new NativeAuthAuthenticationToken(
                        clientPrincipal, client, "locked", "wrong", null, null,
                        "device-1", "Pixel", "1.0", Set.of("openid"))))
                        .isInstanceOf(OAuth2AuthenticationException.class);
            }
            assertThatThrownBy(() -> provider.authenticate(new NativeAuthAuthenticationToken(
                    clientPrincipal, client, "locked", "locked-password", null, null,
                    "device-1", "Pixel", "1.0", Set.of("openid"))))
                    .isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                            exception -> assertThat(exception.getError().getErrorCode())
                                    .isEqualTo("account_locked"));

            var raw = new NativeAuthAuthenticationToken(clientPrincipal, client, "alice",
                    "alice-password", null, null, "device-1", "Pixel", "1.0",
                    Set.of("openid", "profile", "offline_access"));
            OAuth2AccessTokenAuthenticationToken result =
                    (OAuth2AccessTokenAuthenticationToken) provider.authenticate(raw);
            assertThat(result.getAccessToken()).isNotNull();
            assertThat(result.getRefreshToken()).isNotNull();
            assertThat(result.getAdditionalParameters()).containsKey("id_token");

            OAuth2RefreshTokenAuthenticationProvider refreshProvider =
                    new OAuth2RefreshTokenAuthenticationProvider(
                            context.getBean(OAuth2AuthorizationService.class),
                            context.getBean(OAuth2TokenGenerator.class));
            OAuth2AccessTokenAuthenticationToken refreshed =
                    (OAuth2AccessTokenAuthenticationToken) refreshProvider.authenticate(
                            new OAuth2RefreshTokenAuthenticationToken(
                                    result.getRefreshToken().getTokenValue(), clientPrincipal,
                                    Set.of("openid"), Map.of()));
            assertThat(refreshed.getAccessToken()).isNotNull();
            assertThat(refreshed.getRefreshToken()).isNotNull();

            IamTotpService totp = context.getBean(IamTotpService.class);
            var enrollment = totp.enroll(user.id());
            String confirmCode = TotpGenerator.generate(Base32.decode(enrollment.secret()),
                    Instant.now(), 6, 30, TotpAlgorithm.SHA1);
            totp.confirm(user.id(), confirmCode);

            NativeAuthMfaRequiredException mfaRequired = null;
            try {
                provider.authenticate(new NativeAuthAuthenticationToken(clientPrincipal, client,
                        "alice", "alice-password", null, null, "device-1", "Pixel", "1.0",
                        Set.of("openid")));
            } catch (NativeAuthMfaRequiredException exception) {
                mfaRequired = exception;
            }
            assertThat(mfaRequired).isNotNull();
            String mfaToken = String.valueOf(mfaRequired.parameters().get("mfa_token"));
            String otp = TotpGenerator.generate(Base32.decode(enrollment.secret()),
                    Instant.now().plusSeconds(30), 6, 30, TotpAlgorithm.SHA1);

            OAuth2AccessTokenAuthenticationToken mfaResult =
                    (OAuth2AccessTokenAuthenticationToken) provider.authenticate(
                            new NativeAuthAuthenticationToken(clientPrincipal, client, null, null,
                                    mfaToken, otp, "device-1", "Pixel", "1.0", Set.of("openid")));
            assertThat(mfaResult.getAccessToken()).isNotNull();
            assertThat(mfaResult.getAdditionalParameters()).containsKey("id_token");
        });
    }

    @EnableIamServer
    static class TestApplication {

        @Bean
        IdGenerator<String> idGenerator() {
            return TestIdGenerators.sequential();
        }
    }
}
