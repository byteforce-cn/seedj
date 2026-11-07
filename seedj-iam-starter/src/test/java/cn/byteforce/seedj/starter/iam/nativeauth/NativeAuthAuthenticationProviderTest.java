package cn.byteforce.seedj.starter.iam.nativeauth;

import cn.byteforce.seedj.core.security.SecurityAuditEvent;
import cn.byteforce.seedj.core.security.SecurityAuditSink;
import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamMfaChallenge;
import cn.byteforce.seedj.iam.IamSubjectAuthorization;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamUserRepository;
import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.starter.iam.TestIdGenerators;
import cn.byteforce.seedj.starter.iam.identity.IamAuthorizationService;
import cn.byteforce.seedj.starter.iam.identity.IamIdentityService;
import cn.byteforce.seedj.starter.iam.identity.IamMfaChallengeService;
import cn.byteforce.seedj.starter.iam.identity.IamTotpService;
import cn.byteforce.seedj.starter.iam.security.NativeAuthGrantType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

import java.time.Instant;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** native-auth 授权提供方：客户端校验、登录节流、MFA 两阶段与令牌装配。 */
class NativeAuthAuthenticationProviderTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    private final IamIdentityService identities = mock(IamIdentityService.class);
    private final InMemoryIamUserRepository users = new InMemoryIamUserRepository();
    private final IamAuthorizationService authorization = mock(IamAuthorizationService.class);
    private final IamTotpService totp = mock(IamTotpService.class);
    private final IamMfaChallengeService challenges = mock(IamMfaChallengeService.class);
    private final OAuth2AuthorizationService authorizations = mock(OAuth2AuthorizationService.class);
    private final IamLoginRateLimiter rateLimiter = mock(IamLoginRateLimiter.class);
    private final RecordingAuditSink audit = new RecordingAuditSink();
    private final StubTokenGenerator tokenGenerator = new StubTokenGenerator();

    private IamUser user;
    private RegisteredClient client;
    private NativeAuthAuthenticationProvider provider;

    @BeforeEach
    void setUp() {
        user = users.create(IamUser.create("u-1", "default", "alice", "Alice", null, true),
                "hash");
        when(authorization.effective("default", "u-1")).thenReturn(new IamSubjectAuthorization(
                Set.of("ops"), Set.of("order.read"), Set.of("finance")));
        when(identities.authenticate("default", "alice", "alice-password")).thenReturn(user);
        client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("native-app")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(NativeAuthGrantType.NATIVE_AUTH)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .scope("openid")
                .scope("profile")
                .build();
        provider = new NativeAuthAuthenticationProvider(identities, users, authorization, totp,
                challenges, authorizations, tokenGenerator, IamProperties.defaults(), rateLimiter,
                audit, TestIdGenerators.sequential());
        AuthorizationServerSettings settings = AuthorizationServerSettings.builder()
                .issuer("https://iam.test").build();
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
    }

    @AfterEach
    void clearContext() {
        AuthorizationServerContextHolder.resetContext();
    }

    @Test
    @DisplayName("supports 只接受 native-auth 令牌")
    void supportsNativeTokenOnly() {
        assertThat(provider.supports(NativeAuthAuthenticationToken.class)).isTrue();
        assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isFalse();
    }

    @Test
    @DisplayName("缺少客户端或客户端主体：invalid_client")
    void missingClientRejected() {
        assertThat(errorCode(() -> provider.authenticate(
                new NativeAuthAuthenticationToken(null, client, "alice", "alice-password",
                        null, null, null, null, null, Set.of()))))
                .isEqualTo("invalid_client");
        assertThat(errorCode(() -> provider.authenticate(
                new NativeAuthAuthenticationToken(null, null, "alice", "alice-password",
                        null, null, null, null, null, Set.of()))))
                .isEqualTo("invalid_client");
    }

    @Test
    @DisplayName("客户端未注册 native-auth grant：unauthorized_client")
    void clientWithoutGrantRejected() {
        RegisteredClient plain = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("plain-app")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("openid")
                .build();

        assertThat(errorCode(() -> provider.authenticate(
                new NativeAuthAuthenticationToken(clientPrincipal(plain), plain, "alice",
                        "alice-password", null, null, null, null, null, Set.of()))))
                .isEqualTo("unauthorized_client");
    }

    @Test
    @DisplayName("请求未注册 scope：invalid_scope")
    void unregisteredScopeRejected() {
        assertThat(errorCode(() -> provider.authenticate(
                new NativeAuthAuthenticationToken(clientPrincipal(client), client, "alice",
                        "alice-password", null, null, null, null, null, Set.of("admin")))))
                .isEqualTo("invalid_scope");
    }

    @Test
    @DisplayName("节流锁定：account_locked")
    void rateLimitedRejected() {
        doThrow(new IamException(IamErrorCode.ACCOUNT_LOCKED, "locked"))
                .when(rateLimiter).check("default", "alice", "native-app");

        assertThat(errorCode(() -> provider.authenticate(
                new NativeAuthAuthenticationToken(clientPrincipal(client), client, "alice",
                        "alice-password", null, null, null, null, null, Set.of()))))
                .isEqualTo("account_locked");
        verify(identities, never()).authenticate(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("凭据错误：invalid_grant + 失败计数 + 失败审计")
    void badCredentialsCountedAndAudited() {
        when(identities.authenticate("default", "alice", "bad"))
                .thenThrow(new IamException(IamErrorCode.INVALID_CREDENTIALS, "bad password"));

        assertThat(errorCode(() -> provider.authenticate(
                new NativeAuthAuthenticationToken(clientPrincipal(client), client, "alice",
                        "bad", null, null, "device-1", "Pixel", "1.0", Set.of()))))
                .isEqualTo("invalid_grant");
        verify(rateLimiter).failure("default", "alice", "native-app");
        assertThat(audit.events).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo("FAILURE");
            assertThat(event.reason()).isEqualTo(IamErrorCode.INVALID_CREDENTIALS.name());
            assertThat(event.actorId()).isEmpty();
            assertThat(event.details()).containsEntry("username", "alice");
        });
    }

    @Test
    @DisplayName("账号锁定错误映射为 account_locked")
    void lockedAccountMapsToAccountLocked() {
        when(identities.authenticate("default", "alice", "alice-password"))
                .thenThrow(new IamException(IamErrorCode.ACCOUNT_LOCKED, "locked"));

        assertThat(errorCode(() -> provider.authenticate(
                new NativeAuthAuthenticationToken(clientPrincipal(client), client, "alice",
                        "alice-password", null, null, null, null, null, Set.of()))))
                .isEqualTo("account_locked");
    }

    @Test
    @DisplayName("启用 TOTP 且未带 otp：返回 mfa_required 与挑战令牌")
    void mfaRequiredIssuesChallenge() {
        when(totp.isEnabled("u-1")).thenReturn(true);
        when(challenges.create("u-1", "native-app", "device-1")).thenReturn("mfa-token-1");

        assertThatThrownBy(() -> provider.authenticate(new NativeAuthAuthenticationToken(
                clientPrincipal(client), client, "alice", "alice-password", null, null,
                "device-1", "Pixel", "1.0", Set.of())))
                .isInstanceOfSatisfying(NativeAuthMfaRequiredException.class, exception -> {
                    assertThat(exception.getError().getErrorCode()).isEqualTo("mfa_required");
                    assertThat(exception.parameters())
                            .containsEntry("mfa_token", "mfa-token-1")
                            .containsEntry("mfa_methods", List.of("totp"));
                });
        assertThat(audit.events).singleElement()
                .satisfies(event -> assertThat(event.outcome()).isEqualTo("MFA_REQUIRED"));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("OAuth 授权保存 amr=acr=pwd 且签发访问/刷新令牌")
    void passwordLoginIssuesTokens() {
        OAuth2AccessTokenAuthenticationToken result =
                (OAuth2AccessTokenAuthenticationToken) provider.authenticate(
                        new NativeAuthAuthenticationToken(clientPrincipal(client), client,
                                "alice", "alice-password", null, null, null, null, null,
                                Set.of("openid")));

        assertThat(result.getAccessToken().getTokenValue()).isEqualTo("access-1");
        assertThat(result.getRefreshToken().getTokenValue()).isEqualTo("refresh-1");
        assertThat(result.getAdditionalParameters()).doesNotContainKey(
                OidcParameterNames.ID_TOKEN);
        OAuth2Authorization saved = savedAuthorization();
        assertThat(authoritiesOf(saved))
                .contains("ROLE_ops", "PERM_order.read", "GROUP_finance", "AMR_PWD",
                        "ACR_urn:seedj:acr:pwd")
                .noneMatch(authority -> authority.startsWith("AMR_TOTP"));
        assertThat(audit.events).singleElement()
                .satisfies(event -> assertThat(event.outcome()).isEqualTo("SUCCESS"));

        assertThat((List<String>) saved.getAttribute("amr")).containsExactly("pwd");
        assertThat(saved.<String>getAttribute("acr")).isEqualTo("urn:seedj:acr:pwd");
        assertThat(saved.<String>getAttribute("device_id")).isEmpty();
        assertThat(saved.<String>getAttribute("device_name")).isEmpty();
        assertThat(saved.<String>getAttribute("app_version")).isEmpty();
    }

    @Test
    @DisplayName("openid scope 下发 id_token")
    void openidScopeAddsIdToken() {
        tokenGenerator.idToken = jwt("id-token-1");

        OAuth2AccessTokenAuthenticationToken result =
                (OAuth2AccessTokenAuthenticationToken) provider.authenticate(
                        new NativeAuthAuthenticationToken(clientPrincipal(client), client,
                                "alice", "alice-password", null, null, null, null, null,
                                Set.of("openid")));

        assertThat(result.getAdditionalParameters())
                .containsEntry(OidcParameterNames.ID_TOKEN, "id-token-1");
    }

    @Test
    @DisplayName("id_token 生成器返回非 Jwt 令牌时直接附加")
    void nonJwtIdTokenIsAttached() {
        tokenGenerator.idToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "opaque-id-token", NOW, NOW.plusSeconds(300), Set.of("openid"));

        OAuth2AccessTokenAuthenticationToken result =
                (OAuth2AccessTokenAuthenticationToken) provider.authenticate(
                        new NativeAuthAuthenticationToken(clientPrincipal(client), client,
                                "alice", "alice-password", null, null, null, null, null,
                                Set.of("openid")));

        assertThat(result.getAdditionalParameters())
                .containsEntry(OidcParameterNames.ID_TOKEN, "opaque-id-token");
    }

    @Test
    @DisplayName("access token 生成器返回 Jwt 时转换为 bearer 访问令牌")
    void jwtAccessTokenIsWrapped() {
        tokenGenerator.accessToken = jwt("jwt-access");

        OAuth2AccessTokenAuthenticationToken result =
                (OAuth2AccessTokenAuthenticationToken) provider.authenticate(
                        new NativeAuthAuthenticationToken(clientPrincipal(client), client,
                                "alice", "alice-password", null, null, null, null, null,
                                Set.of("profile")));

        assertThat(result.getAccessToken().getTokenValue()).isEqualTo("jwt-access");
        assertThat(result.getAccessToken().getScopes()).containsExactly("profile");
    }

    @Test
    @DisplayName("access token 生成器无产出：server_error")
    void missingAccessTokenFails() {
        tokenGenerator.accessToken = null;

        assertThat(errorCode(() -> provider.authenticate(
                new NativeAuthAuthenticationToken(clientPrincipal(client), client, "alice",
                        "alice-password", null, null, null, null, null, Set.of()))))
                .isEqualTo("server_error");
    }

    @Test
    @DisplayName("缺用户名与 mfa_token：invalid_request")
    void missingCredentialsRejected() {
        assertThat(errorCode(() -> provider.authenticate(
                new NativeAuthAuthenticationToken(clientPrincipal(client), client, null, null,
                        null, null, null, null, null, Set.of()))))
                .isEqualTo("invalid_request");
    }

    @Test
    @DisplayName("挑战令牌未知：mfa_invalid")
    void unknownMfaTokenRejected() {
        when(challenges.require("mfa-unknown")).thenThrow(
                new IamException(IamErrorCode.MFA_CHALLENGE_INVALID, "invalid mfa_token"));

        assertThat(errorCode(() -> provider.authenticate(
                new NativeAuthAuthenticationToken(clientPrincipal(client), client, null, null,
                        "mfa-unknown", "123456", null, null, null, Set.of()))))
                .isEqualTo("mfa_invalid");
    }

    @Test
    @DisplayName("仅 mfa_token 登录：用户缺失或客户端不匹配均 invalid_grant")
    void mfaOnlyLoginValidatesChallengeOwner() {
        when(challenges.require("mfa-1")).thenReturn(challenge("u-1", "native-app", "device-1"));
        when(challenges.require("mfa-2")).thenReturn(challenge("u-missing", "native-app",
                "device-1"));
        when(challenges.require("mfa-3")).thenReturn(challenge("u-1", "other-app", "device-1"));

        assertThat(errorCode(() -> provider.authenticate(new NativeAuthAuthenticationToken(
                clientPrincipal(client), client, null, null, "mfa-2", "123456", null, null,
                null, Set.of())))).isEqualTo("invalid_grant");
        assertThat(errorCode(() -> provider.authenticate(new NativeAuthAuthenticationToken(
                clientPrincipal(client), client, null, null, "mfa-3", "123456", null, null,
                null, Set.of())))).isEqualTo("invalid_grant");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("仅 mfa_token + 有效 otp 登录成功并记录 amr=totp")
    void mfaOnlyLoginSucceeds() {
        IamMfaChallenge challenge = challenge("u-1", "native-app", "device-1");
        when(challenges.require("mfa-1")).thenReturn(challenge);

        OAuth2AccessTokenAuthenticationToken result =
                (OAuth2AccessTokenAuthenticationToken) provider.authenticate(
                        new NativeAuthAuthenticationToken(clientPrincipal(client), client,
                                null, null, "mfa-1", "123456", "device-1", "Pixel", "1.0",
                                Set.of("openid")));

        assertThat(result.getAccessToken()).isNotNull();
        verify(totp).verify("u-1", "123456");
        verify(challenges).consume(challenge);
        OAuth2Authorization saved = savedAuthorization();
        assertThat(authoritiesOf(saved)).contains("AMR_TOTP", "ACR_urn:seedj:acr:totp");
        assertThat((List<String>) saved.getAttribute("amr")).containsExactly("pwd", "totp");
    }

    @Test
    @DisplayName("挑战与当前用户/设备不匹配：invalid_grant")
    void mfaChallengeMismatchRejected() {
        when(totp.isEnabled("u-1")).thenReturn(true);
        when(challenges.require("mfa-1")).thenReturn(challenge("u-other", "native-app",
                "device-1"));

        assertThat(errorCode(() -> provider.authenticate(new NativeAuthAuthenticationToken(
                clientPrincipal(client), client, "alice", "alice-password", "mfa-1",
                "123456", "device-1", null, null, Set.of())))).isEqualTo("invalid_grant");

        when(challenges.require("mfa-2")).thenReturn(challenge("u-1", "native-app", "device-1"));

        assertThat(errorCode(() -> provider.authenticate(new NativeAuthAuthenticationToken(
                clientPrincipal(client), client, "alice", "alice-password", "mfa-2",
                "123456", "device-9", null, null, Set.of())))).isEqualTo("invalid_grant");
    }

    @Test
    @DisplayName("otp 为空：mfa_invalid 并记录挑战失败")
    void blankOtpRejected() {
        when(totp.isEnabled("u-1")).thenReturn(true);
        IamMfaChallenge challenge = challenge("u-1", "native-app", "device-1");
        when(challenges.require("mfa-1")).thenReturn(challenge);

        assertThat(errorCode(() -> provider.authenticate(new NativeAuthAuthenticationToken(
                clientPrincipal(client), client, "alice", "alice-password", "mfa-1", " ",
                "device-1", null, null, Set.of())))).isEqualTo("mfa_invalid");
        verify(challenges).failure(challenge);
    }

    @Test
    @DisplayName("otp 与恢复码都无效：mfa_invalid 并记录挑战失败")
    void invalidOtpRejected() {
        when(totp.isEnabled("u-1")).thenReturn(true);
        IamMfaChallenge challenge = challenge("u-1", "native-app", "device-1");
        when(challenges.require("mfa-1")).thenReturn(challenge);
        doThrow(new IamException(IamErrorCode.TOTP_INVALID, "bad otp"))
                .when(totp).verify("u-1", "000000");
        doThrow(new IamException(IamErrorCode.RECOVERY_CODE_INVALID, "bad recovery"))
                .when(totp).verifyRecoveryCode("u-1", "000000");

        assertThat(errorCode(() -> provider.authenticate(new NativeAuthAuthenticationToken(
                clientPrincipal(client), client, "alice", "alice-password", "mfa-1",
                "000000", "device-1", null, null, Set.of())))).isEqualTo("mfa_invalid");
        verify(challenges).failure(challenge);
        verify(challenges, never()).consume(challenge);
    }

    @Test
    @DisplayName("恢复码登录：amr 含 recovery、acr 为 recovery 级别")
    void recoveryCodeLoginSucceeds() {
        when(totp.isEnabled("u-1")).thenReturn(true);
        IamMfaChallenge challenge = challenge("u-1", "native-app", "device-1");
        when(challenges.require("mfa-1")).thenReturn(challenge);
        doThrow(new IamException(IamErrorCode.TOTP_INVALID, "bad otp"))
                .when(totp).verify("u-1", "RECOVERY-1");
        when(totp.verifyRecoveryCode("u-1", "RECOVERY-1")).thenReturn(true);

        OAuth2AccessTokenAuthenticationToken result =
                (OAuth2AccessTokenAuthenticationToken) provider.authenticate(
                        new NativeAuthAuthenticationToken(clientPrincipal(client), client,
                                "alice", "alice-password", "mfa-1", "RECOVERY-1", "device-1",
                                null, null, Set.of()));

        assertThat(authoritiesOf(savedAuthorization()))
                .contains("AMR_RECOVERY", "ACR_urn:seedj:acr:recovery");
        verify(challenges).consume(challenge);
    }

    private OAuth2Authorization savedAuthorization() {
        ArgumentCaptor<OAuth2Authorization> captor =
                ArgumentCaptor.forClass(OAuth2Authorization.class);
        verify(authorizations).save(captor.capture());
        return captor.getValue();
    }

    private static List<String> authoritiesOf(OAuth2Authorization authorization) {
        Authentication principal = authorization.getAttribute(Principal.class.getName());
        List<String> authorities = new ArrayList<>();
        for (GrantedAuthority authority : principal.getAuthorities()) {
            authorities.add(authority.getAuthority());
        }
        return authorities;
    }

    private static String errorCode(Runnable action) {
        try {
            action.run();
        } catch (OAuth2AuthenticationException exception) {
            return exception.getError().getErrorCode();
        }
        throw new AssertionError("expected an OAuth2AuthenticationException");
    }

    private static OAuth2ClientAuthenticationToken clientPrincipal(RegisteredClient client) {
        return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE,
                client.getClientId());
    }

    private static OAuth2AccessToken accessToken(String value) {
        return new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, value, NOW,
                NOW.plusSeconds(300), Set.of("openid"));
    }

    private static Jwt jwt(String value) {
        return Jwt.withTokenValue(value)
                .header("alg", "none")
                .subject("alice")
                .claim("scope", "openid")
                .issuedAt(NOW)
                .expiresAt(NOW.plusSeconds(300))
                .build();
    }

    private static IamMfaChallenge challenge(String userId, String clientId, String deviceId) {
        return new IamMfaChallenge("c-1", userId, clientId, deviceId, "hash", NOW.plusSeconds(300),
                false, 0, 5);
    }

    private static final class StubTokenGenerator implements OAuth2TokenGenerator<OAuth2Token> {

        private OAuth2Token accessToken = accessToken("access-1");

        private OAuth2Token refreshToken = new OAuth2RefreshToken("refresh-1", NOW,
                NOW.plusSeconds(3600));

        private OAuth2Token idToken;

        @Override
        public OAuth2Token generate(OAuth2TokenContext context) {
            OAuth2TokenType type = context.getTokenType();
            if (OAuth2TokenType.ACCESS_TOKEN.equals(type)) {
                return accessToken;
            }
            if (OAuth2TokenType.REFRESH_TOKEN.equals(type)) {
                return refreshToken;
            }
            if (new OAuth2TokenType(OidcParameterNames.ID_TOKEN).equals(type)) {
                return idToken;
            }
            return null;
        }
    }

    private static final class RecordingAuditSink implements SecurityAuditSink {

        private final List<SecurityAuditEvent> events = new ArrayList<>();

        @Override
        public void record(SecurityAuditEvent event) {
            events.add(event);
        }
    }
}
