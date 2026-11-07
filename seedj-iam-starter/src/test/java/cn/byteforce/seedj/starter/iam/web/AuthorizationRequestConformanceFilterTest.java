package cn.byteforce.seedj.starter.iam.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

/** RFC 6749 / OIDC Core 前置校验的行为测试：只断言状态码、重定向错误码、Cookie 与放行结果。 */
class AuthorizationRequestConformanceFilterTest {

    private static final String AUTHORIZE = "/oauth2/authorize";
    private static final String REDIRECT_URI = "https://app.example.com/cb";

    private final Map<String, RegisteredClient> clients = new LinkedHashMap<>();
    private final AtomicBoolean chainInvoked = new AtomicBoolean();
    private OAuth2AuthorizationConsent consent;

    private AuthorizationRequestConformanceFilter filter;

    @BeforeEach
    void setUp() {
        clients.clear();
        chainInvoked.set(false);
        consent = null;
        filter =
                new AuthorizationRequestConformanceFilter(
                        new RegisteredClientRepository() {
                            @Override
                            public void save(RegisteredClient registeredClient) {
                                clients.put(registeredClient.getClientId(), registeredClient);
                            }

                            @Override
                            public RegisteredClient findById(String id) {
                                return clients.values().stream()
                                        .filter(c -> c.getId().equals(id))
                                        .findFirst()
                                        .orElse(null);
                            }

                            @Override
                            public RegisteredClient findByClientId(String clientId) {
                                return clients.get(clientId);
                            }
                        },
                        new OAuth2AuthorizationConsentService() {
                            @Override
                            public void save(OAuth2AuthorizationConsent authorizationConsent) {}

                            @Override
                            public void remove(OAuth2AuthorizationConsent authorizationConsent) {}

                            @Override
                            public OAuth2AuthorizationConsent findById(
                                    String clientId, String principalName) {
                                return consent;
                            }
                        },
                        AuthorizationServerSettings.builder().issuer("https://iam.test").build());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // shouldNotFilter
    // ------------------------------------------------------------------

    @Test
    void ignoresNonAuthorizationEndpoint() throws Exception {
        register(client(false, false, REDIRECT_URI));

        MockHttpServletResponse response = run(request("GET", "/oauth2/token", "client_id", "web"));

        assertThat(chainInvoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void ignoresPushedAuthorizationRequest() throws Exception {
        register(client(false, false, REDIRECT_URI));

        run(
                request(
                        "GET",
                        AUTHORIZE,
                        "client_id",
                        "nobody",
                        "request_uri",
                        "urn:ietf:params:oauth:request_uri:x"));

        assertThat(chainInvoked).isTrue();
    }

    @Test
    void ignoresPostWithoutResponseType() throws Exception {
        register(client(false, false, REDIRECT_URI));

        run(request("POST", AUTHORIZE, "client_id", "web", "decision", "approve"));

        assertThat(chainInvoked).isTrue();
    }

    @Test
    void appliesWhenContextPathIsSet() throws Exception {
        register(client(false, false, REDIRECT_URI));
        MockHttpServletRequest request =
                request(
                        "GET",
                        AUTHORIZE,
                        "client_id",
                        "web",
                        "redirect_uri",
                        REDIRECT_URI,
                        "response_type",
                        "code");
        request.setContextPath("/iam");
        request.setRequestURI("/iam" + AUTHORIZE);

        MockHttpServletResponse response = run(request);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chainInvoked).isTrue();
    }

    // ------------------------------------------------------------------
    // 400：不得重定向到未校验的 redirect_uri
    // ------------------------------------------------------------------

    @Test
    void missingClientIdIsBadRequest() throws Exception {
        MockHttpServletResponse response = run(request("GET", AUTHORIZE, "response_type", "code"));

        assertJsonError(response, "invalid_request");
        assertThat(response.getRedirectedUrl()).isNull();
    }

    @Test
    void blankClientIdIsBadRequest() throws Exception {
        MockHttpServletResponse response =
                run(request("GET", AUTHORIZE, "client_id", " ", "response_type", "code"));

        assertJsonError(response, "invalid_request");
    }

    @Test
    void unregisteredClientIdIsBadRequest() throws Exception {
        register(client(false, false, REDIRECT_URI));

        MockHttpServletResponse response =
                run(
                        request(
                                "GET",
                                AUTHORIZE,
                                "client_id",
                                "unknown",
                                "redirect_uri",
                                REDIRECT_URI,
                                "response_type",
                                "code"));

        assertJsonError(response, "invalid_client");
        assertThat(response.getRedirectedUrl()).isNull();
    }

    @Test
    void missingRedirectUriIsBadRequestWhenClientHasSeveral() throws Exception {
        register(client(false, false, REDIRECT_URI, "https://app.example.com/other"));

        MockHttpServletResponse response =
                run(request("GET", AUTHORIZE, "client_id", "web", "response_type", "code"));

        assertJsonError(response, "invalid_request");
        assertThat(response.getContentAsString()).contains("redirect_uri is required");
    }

    @Test
    void singleRegisteredRedirectUriIsUsedWhenOmitted() throws Exception {
        register(client(false, false, REDIRECT_URI));

        MockHttpServletResponse response =
                run(
                        request(
                                "GET",
                                AUTHORIZE,
                                "client_id",
                                "web",
                                "response_type",
                                "code",
                                "scope",
                                "openid"));

        assertThat(chainInvoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void unregisteredRedirectUriIsBadRequest() throws Exception {
        register(client(false, false, REDIRECT_URI));

        MockHttpServletResponse response =
                run(
                        request(
                                "GET",
                                AUTHORIZE,
                                "client_id",
                                "web",
                                "redirect_uri",
                                "https://evil.example/cb",
                                "response_type",
                                "code"));

        assertJsonError(response, "invalid_request");
        assertThat(response.getContentAsString()).contains("redirect_uri is not registered");
    }

    // ------------------------------------------------------------------
    // 302 带错误码（redirect_uri 已校验）
    // ------------------------------------------------------------------

    @Test
    void duplicateSingletonParameterIsRedirectedAsInvalidRequest() throws Exception {
        register(client(false, false, REDIRECT_URI));

        MockHttpServletResponse response =
                run(
                        request(
                                "GET",
                                AUTHORIZE,
                                "client_id",
                                "web",
                                "client_id",
                                "web",
                                "redirect_uri",
                                REDIRECT_URI,
                                "response_type",
                                "code",
                                "state",
                                "s1"));

        assertRedirectError(response, "invalid_request", "s1");
    }

    @Test
    void missingResponseTypeIsRedirectedAsInvalidRequest() throws Exception {
        register(client(false, false, REDIRECT_URI));

        MockHttpServletResponse response =
                run(request("GET", AUTHORIZE, "client_id", "web", "redirect_uri", REDIRECT_URI));

        assertRedirectError(response, "invalid_request", null);
    }

    @Test
    void unsupportedResponseTypeIsRedirected() throws Exception {
        register(client(false, false, REDIRECT_URI));

        MockHttpServletResponse response =
                run(
                        request(
                                "GET",
                                AUTHORIZE,
                                "client_id",
                                "web",
                                "redirect_uri",
                                REDIRECT_URI,
                                "response_type",
                                "token"));

        assertRedirectError(response, "unsupported_response_type", null);
    }

    @Test
    void duplicateScopeIsRedirectedAsInvalidScope() throws Exception {
        register(client(false, false, REDIRECT_URI));

        MockHttpServletResponse response =
                run(
                        request(
                                "GET",
                                AUTHORIZE,
                                "client_id",
                                "web",
                                "redirect_uri",
                                REDIRECT_URI,
                                "response_type",
                                "code",
                                "scope",
                                "openid openid"));

        assertRedirectError(response, "invalid_scope", null);
    }

    @Test
    void scopeOutsideAllowedCharacterSetIsRedirectedAsInvalidScope() throws Exception {
        register(client(false, false, REDIRECT_URI));

        MockHttpServletResponse response =
                run(
                        request(
                                "GET",
                                AUTHORIZE,
                                "client_id",
                                "web",
                                "redirect_uri",
                                REDIRECT_URI,
                                "response_type",
                                "code",
                                "scope",
                                "open\"id"));

        assertRedirectError(response, "invalid_scope", null);
    }

    @Test
    void unregisteredScopeIsRedirectedAsInvalidScope() throws Exception {
        register(client(false, false, REDIRECT_URI));

        MockHttpServletResponse response =
                run(
                        request(
                                "GET",
                                AUTHORIZE,
                                "client_id",
                                "web",
                                "redirect_uri",
                                REDIRECT_URI,
                                "response_type",
                                "code",
                                "scope",
                                "openid admin"));

        assertRedirectError(response, "invalid_scope", null);
    }

    @Test
    void requiredPkceWithoutChallengeIsRedirected() throws Exception {
        register(client(true, false, REDIRECT_URI));

        MockHttpServletResponse response =
                run(
                        request(
                                "GET",
                                AUTHORIZE,
                                "client_id",
                                "web",
                                "redirect_uri",
                                REDIRECT_URI,
                                "response_type",
                                "code"));

        assertRedirectError(response, "invalid_request", null);
        assertThat(response.getRedirectedUrl()).contains("code_challenge");
    }

    @Test
    void codeChallengeMethodWithoutChallengeIsRedirected() throws Exception {
        register(client(false, false, REDIRECT_URI));

        MockHttpServletResponse response =
                run(
                        request(
                                "GET",
                                AUTHORIZE,
                                "client_id",
                                "web",
                                "redirect_uri",
                                REDIRECT_URI,
                                "response_type",
                                "code",
                                "code_challenge_method",
                                "S256"));

        assertRedirectError(response, "invalid_request", null);
    }

    @Test
    void challengeWithoutMethodIsRedirected() throws Exception {
        register(client(false, false, REDIRECT_URI));

        MockHttpServletResponse response =
                run(
                        request(
                                "GET",
                                AUTHORIZE,
                                "client_id",
                                "web",
                                "redirect_uri",
                                REDIRECT_URI,
                                "response_type",
                                "code",
                                "code_challenge",
                                challenge()));

        assertRedirectError(response, "invalid_request", null);
    }

    @Test
    void nonS256ChallengeMethodIsRedirected() throws Exception {
        register(client(false, false, REDIRECT_URI));

        MockHttpServletResponse response =
                run(
                        request(
                                "GET",
                                AUTHORIZE,
                                "client_id",
                                "web",
                                "redirect_uri",
                                REDIRECT_URI,
                                "response_type",
                                "code",
                                "code_challenge",
                                challenge(),
                                "code_challenge_method",
                                "plain"));

        assertRedirectError(response, "invalid_request", null);
    }

    @Test
    void challengeWithWrongLengthIsRedirected() throws Exception {
        register(client(false, false, REDIRECT_URI));

        MockHttpServletResponse response =
                run(
                        request(
                                "GET",
                                AUTHORIZE,
                                "client_id",
                                "web",
                                "redirect_uri",
                                REDIRECT_URI,
                                "response_type",
                                "code",
                                "code_challenge",
                                challenge().substring(1),
                                "code_challenge_method",
                                "S256"));

        assertRedirectError(response, "invalid_request", null);
    }

    @Test
    void challengeWithIllegalCharacterIsRedirected() throws Exception {
        register(client(false, false, REDIRECT_URI));

        MockHttpServletResponse response =
                run(
                        request(
                                "GET",
                                AUTHORIZE,
                                "client_id",
                                "web",
                                "redirect_uri",
                                REDIRECT_URI,
                                "response_type",
                                "code",
                                "code_challenge",
                                "!" + challenge().substring(1),
                                "code_challenge_method",
                                "S256"));

        assertRedirectError(response, "invalid_request", null);
    }

    @Test
    void unsupportedResponseModeIsRedirected() throws Exception {
        register(client(false, false, REDIRECT_URI));

        MockHttpServletResponse response =
                run(
                        request(
                                "GET",
                                AUTHORIZE,
                                "client_id",
                                "web",
                                "redirect_uri",
                                REDIRECT_URI,
                                "response_type",
                                "code",
                                "response_mode",
                                "fragment"));

        assertRedirectError(response, "invalid_request", null);
    }

    @Test
    void negativeMaxAgeIsRedirected() throws Exception {
        register(client(false, false, REDIRECT_URI));

        MockHttpServletResponse response =
                run(
                        request(
                                "GET",
                                AUTHORIZE,
                                "client_id",
                                "web",
                                "redirect_uri",
                                REDIRECT_URI,
                                "response_type",
                                "code",
                                "max_age",
                                "-1"));

        assertRedirectError(response, "invalid_request", null);
    }

    @Test
    void nonNumericMaxAgeIsRedirected() throws Exception {
        register(client(false, false, REDIRECT_URI));

        MockHttpServletResponse response =
                run(
                        request(
                                "GET",
                                AUTHORIZE,
                                "client_id",
                                "web",
                                "redirect_uri",
                                REDIRECT_URI,
                                "response_type",
                                "code",
                                "max_age",
                                "soon"));

        assertRedirectError(response, "invalid_request", null);
    }

    @Test
    void emptyPromptIsRedirected() throws Exception {
        register(client(false, false, REDIRECT_URI));

        MockHttpServletResponse response =
                run(
                        request(
                                "GET",
                                AUTHORIZE,
                                "client_id",
                                "web",
                                "redirect_uri",
                                REDIRECT_URI,
                                "response_type",
                                "code",
                                "prompt",
                                " "));

        assertRedirectError(response, "invalid_request", null);
    }

    @Test
    void promptNoneCombinedWithOtherValuesIsRedirected() throws Exception {
        register(client(false, false, REDIRECT_URI));

        MockHttpServletResponse response =
                run(
                        request(
                                "GET",
                                AUTHORIZE,
                                "client_id",
                                "web",
                                "redirect_uri",
                                REDIRECT_URI,
                                "response_type",
                                "code",
                                "prompt",
                                "none login"));

        assertRedirectError(response, "invalid_request", null);
    }

    // ------------------------------------------------------------------
    // prompt=none
    // ------------------------------------------------------------------

    @Test
    void promptNoneWithoutSessionReturnsLoginRequired() throws Exception {
        register(client(false, false, REDIRECT_URI));

        MockHttpServletResponse response =
                run(
                        request(
                                "GET",
                                AUTHORIZE,
                                "client_id",
                                "web",
                                "redirect_uri",
                                REDIRECT_URI,
                                "response_type",
                                "code",
                                "prompt",
                                "none"));

        assertRedirectError(response, "login_required", null);
    }

    @Test
    void promptNoneWithExpiredSessionReturnsLoginRequired() throws Exception {
        register(client(false, false, REDIRECT_URI));
        authenticate("alice");
        MockHttpServletRequest request =
                request(
                        "GET",
                        AUTHORIZE,
                        "client_id",
                        "web",
                        "redirect_uri",
                        REDIRECT_URI,
                        "response_type",
                        "code",
                        "prompt",
                        "none",
                        "max_age",
                        "60");
        request.setSession(oldSession(120_000));

        MockHttpServletResponse response = run(request);

        assertRedirectError(response, "login_required", null);
    }

    @Test
    void promptNoneWithConsentRequiredReturnsConsentRequired() throws Exception {
        register(client(false, true, REDIRECT_URI));
        authenticate("alice");

        MockHttpServletResponse response =
                run(
                        request(
                                "GET",
                                AUTHORIZE,
                                "client_id",
                                "web",
                                "redirect_uri",
                                REDIRECT_URI,
                                "response_type",
                                "code",
                                "scope",
                                "openid profile",
                                "prompt",
                                "none"));

        assertRedirectError(response, "consent_required", null);
    }

    @Test
    void promptNoneWithExistingConsentIsAllowed() throws Exception {
        register(client(false, true, REDIRECT_URI));
        authenticate("alice");
        consent = OAuth2AuthorizationConsent.withId("client-1", "alice").scope("profile").build();

        MockHttpServletResponse response =
                run(
                        request(
                                "GET",
                                AUTHORIZE,
                                "client_id",
                                "web",
                                "redirect_uri",
                                REDIRECT_URI,
                                "response_type",
                                "code",
                                "scope",
                                "openid profile",
                                "prompt",
                                "none"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chainInvoked).isTrue();
    }

    @Test
    void promptNoneIsAllowedWhenOnlyOpenIdScopeIsRequested() throws Exception {
        register(client(false, true, REDIRECT_URI));
        authenticate("alice");

        MockHttpServletResponse response =
                run(
                        request(
                                "GET",
                                AUTHORIZE,
                                "client_id",
                                "web",
                                "redirect_uri",
                                REDIRECT_URI,
                                "response_type",
                                "code",
                                "scope",
                                "openid",
                                "prompt",
                                "none"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chainInvoked).isTrue();
    }

    // ------------------------------------------------------------------
    // 强制重新认证
    // ------------------------------------------------------------------

    @Test
    void promptLoginForcesReauthentication() throws Exception {
        register(client(false, false, REDIRECT_URI));
        authenticate("alice");

        MockHttpServletResponse response =
                run(
                        request(
                                "GET",
                                AUTHORIZE,
                                "client_id",
                                "web",
                                "redirect_uri",
                                REDIRECT_URI,
                                "response_type",
                                "code",
                                "state",
                                "s1",
                                "prompt",
                                "login"));

        assertReauthentication(response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void maxAgeExceededForcesReauthentication() throws Exception {
        register(client(false, false, REDIRECT_URI));
        authenticate("alice");
        MockHttpServletRequest request =
                request(
                        "GET",
                        AUTHORIZE,
                        "client_id",
                        "web",
                        "redirect_uri",
                        REDIRECT_URI,
                        "response_type",
                        "code",
                        "max_age",
                        "60");
        request.setSession(oldSession(120_000));

        MockHttpServletResponse response = run(request);

        assertReauthentication(response);
    }

    @Test
    void maxAgeWithinSessionAgeIsAllowed() throws Exception {
        register(client(false, false, REDIRECT_URI));
        authenticate("alice");
        MockHttpServletRequest request =
                request(
                        "GET",
                        AUTHORIZE,
                        "client_id",
                        "web",
                        "redirect_uri",
                        REDIRECT_URI,
                        "response_type",
                        "code",
                        "max_age",
                        "600");
        request.setSession(oldSession(1_000));

        run(request);

        assertThat(chainInvoked).isTrue();
    }

    @Test
    void anonymousSessionWithMaxAgeIsNotReauthenticatedTwice() throws Exception {
        register(client(false, false, REDIRECT_URI));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new AnonymousAuthenticationToken(
                                "key",
                                "anonymous",
                                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        MockHttpServletRequest request =
                request(
                        "GET",
                        AUTHORIZE,
                        "client_id",
                        "web",
                        "redirect_uri",
                        REDIRECT_URI,
                        "response_type",
                        "code",
                        "max_age",
                        "60");
        request.setSession(oldSession(120_000));

        MockHttpServletResponse response = run(request);

        assertReauthentication(response);
    }

    @Test
    void validRequestIsPassedThrough() throws Exception {
        register(client(false, false, REDIRECT_URI));
        authenticate("alice");
        MockHttpServletRequest request =
                request(
                        "GET",
                        AUTHORIZE,
                        "client_id",
                        "web",
                        "redirect_uri",
                        REDIRECT_URI,
                        "response_type",
                        "code",
                        "scope",
                        "openid profile",
                        "state",
                        "s1",
                        "nonce",
                        "n1",
                        "code_challenge",
                        challenge(),
                        "code_challenge_method",
                        "S256",
                        "max_age",
                        "120",
                        "prompt",
                        "consent",
                        "login_hint",
                        "alice@example.com");
        request.setSession(oldSession(1_000));

        MockHttpServletResponse response = run(request);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chainInvoked).isTrue();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static String challenge() {
        return "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ";
    }

    private void register(RegisteredClient client) {
        clients.put(client.getClientId(), client);
    }

    private static RegisteredClient client(
            boolean requireProofKey, boolean requireConsent, String... redirectUris) {
        RegisteredClient.Builder builder =
                RegisteredClient.withId("client-1")
                        .clientId("web")
                        .clientName("Web")
                        .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .scope("openid")
                        .scope("profile")
                        .scope("read")
                        .clientSettings(
                                ClientSettings.builder()
                                        .requireProofKey(requireProofKey)
                                        .requireAuthorizationConsent(requireConsent)
                                        .build());
        for (String redirectUri : redirectUris) {
            builder.redirectUri(redirectUri);
        }
        return builder.build();
    }

    private static void authenticate(String name) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                name, "n/a", AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    private static MockHttpSession oldSession(long ageMillis) {
        long creationTime = System.currentTimeMillis() - ageMillis;
        return new MockHttpSession() {
            @Override
            public long getCreationTime() {
                return creationTime;
            }
        };
    }

    private static MockHttpServletRequest request(
            String method, String path, String... nameValues) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        for (int i = 0; i + 1 < nameValues.length; i += 2) {
            request.addParameter(nameValues[i], nameValues[i + 1]);
        }
        return request;
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private final FilterChain chain = (request, response) -> chainInvoked.set(true);

    private static void assertJsonError(MockHttpServletResponse response, String error)
            throws Exception {
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains("\"error\":\"" + error + "\"");
    }

    private static void assertRedirectError(
            MockHttpServletResponse response, String error, String state) {
        assertThat(response.getStatus()).isEqualTo(302);
        String location = response.getRedirectedUrl();
        assertThat(location).startsWith("https://app.example.com/cb?");
        assertThat(location).contains("error=" + error);
        if (state != null) {
            assertThat(location).contains("state=" + state);
        }
    }

    private static void assertReauthentication(MockHttpServletResponse response) {
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost/login");
        String cookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie).contains("SEEDJ_REAUTH=").contains("HttpOnly").contains("SameSite=Lax");
        String encoded = cookie.substring("SEEDJ_REAUTH=".length(), cookie.indexOf(';'));
        String target = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        assertThat(target)
                .startsWith("http://localhost/oauth2/authorize?")
                .contains("client_id=web")
                .doesNotContain("prompt=")
                .doesNotContain("max_age=");
    }
}
