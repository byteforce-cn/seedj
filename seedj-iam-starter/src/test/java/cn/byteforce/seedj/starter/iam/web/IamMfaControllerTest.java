package cn.byteforce.seedj.starter.iam.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.byteforce.seedj.iam.IamErrorCode;
import cn.byteforce.seedj.iam.IamException;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.starter.iam.identity.IamMfaAttemptLimiter;
import cn.byteforce.seedj.starter.iam.identity.IamTotpService;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

/** 浏览器 MFA 挑战：pending 认证升级、AMR/ACR 标注、恢复码与限流。 */
class IamMfaControllerTest {

    private static final IamUser USER =
            IamUser.create("user-1", "default", "alice", "Alice", "alice@example.com", true);
    private static final String PENDING = IamMfaAuthenticationSuccessHandler.PENDING_ATTRIBUTE;

    private final IamUserRepository users = mock(IamUserRepository.class);
    private final IamTotpService totp = mock(IamTotpService.class);

    private IamMfaAttemptLimiter attempts;
    private IamMfaController controller;
    private CsrfToken csrf;

    @BeforeEach
    void setUp() {
        attempts = new IamMfaAttemptLimiter(2, Duration.ofMinutes(5), Clock.systemUTC());
        controller =
                new IamMfaController(
                        users,
                        totp,
                        attempts,
                        IamProperties.defaults(),
                        AuthorizationServerSettings.builder().issuer("https://iam.test").build());
        csrf = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "csrf-token");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void pageWithoutPendingAuthenticationRedirectsToLogin() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mfa");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.page(request, response, csrf);

        assertThat(response.getRedirectedUrl()).isEqualTo("/login");
    }

    @Test
    void pageRendersChallengeWithCsrfToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mfa");
        request.setSession(pendingSession());
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.page(request, response, csrf);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("text/html");
        assertThat(response.getContentAsString())
                .contains("Two-factor authentication")
                .contains("name=\"_csrf\" value=\"csrf-token\"")
                .contains("action=\"/mfa\"");
    }

    @Test
    void verifyWithoutPendingAuthenticationRedirectsToLogin() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mfa");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.verify("123456", request, response, csrf);

        assertThat(response.getRedirectedUrl()).isEqualTo("/login");
    }

    @Test
    void verifyWithUnknownUserFails() throws Exception {
        when(users.findByUserName("default", "alice")).thenReturn(Optional.empty());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mfa");
        request.setSession(pendingSession());
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.verify("123456", request, response, csrf);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Invalid code.");
    }

    @Test
    void verifyWithLockedUserIsTooManyRequests() throws Exception {
        when(users.findByUserName("default", "alice")).thenReturn(Optional.of(USER));
        attempts.failure(USER.id());
        attempts.failure(USER.id());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mfa");
        request.setSession(pendingSession());
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.verify("123456", request, response, csrf);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("Too many attempts");
    }

    @Test
    void validTotpCompletesAuthentication() throws Exception {
        when(users.findByUserName("default", "alice")).thenReturn(Optional.of(USER));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mfa");
        MockHttpSession session = pendingSession();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.verify("123456", request, response, csrf);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        assertThat(session.getAttribute(PENDING)).isNull();
        List<String> authorities =
                AuthorityUtils.authorityListToSet(
                                SecurityContextHolder.getContext()
                                        .getAuthentication()
                                        .getAuthorities())
                        .stream()
                        .sorted()
                        .toList();
        assertThat(authorities).contains("AMR_PWD", "AMR_TOTP", "ACR_urn:seedj:acr:totp");
        assertThat(authorities).anyMatch(authority -> authority.startsWith("AUTH_TIME_"));
        verify(totp).verify(USER.id(), "123456");
    }

    @Test
    void recoveryCodeCompletesAuthenticationWithRecoveryAcr() throws Exception {
        when(users.findByUserName("default", "alice")).thenReturn(Optional.of(USER));
        doThrow(new IamException(IamErrorCode.TOTP_INVALID, "invalid"))
                .when(totp)
                .verify(USER.id(), "RECOVERY-CODE");
        when(totp.verifyRecoveryCode(USER.id(), "RECOVERY-CODE")).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mfa");
        request.setSession(pendingSession());
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.verify("RECOVERY-CODE", request, response, csrf);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(
                        AuthorityUtils.authorityListToSet(
                                SecurityContextHolder.getContext()
                                        .getAuthentication()
                                        .getAuthorities()))
                .contains("AMR_RECOVERY", "ACR_urn:seedj:acr:recovery");
    }

    @Test
    void rejectedCodeFailsAndCountsAnAttempt() throws Exception {
        when(users.findByUserName("default", "alice")).thenReturn(Optional.of(USER));
        doThrow(new IamException(IamErrorCode.TOTP_INVALID, "invalid"))
                .when(totp)
                .verify(USER.id(), "000000");
        doThrow(new IamException(IamErrorCode.RECOVERY_CODE_INVALID, "invalid"))
                .when(totp)
                .verifyRecoveryCode(USER.id(), "000000");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mfa");
        request.setSession(pendingSession());
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.verify("000000", request, response, csrf);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Invalid code.");
        attempts.failure(USER.id());
        assertThat(attempts.locked(USER.id())).isTrue();
    }

    @Test
    void nonTotpFailureFailsWithoutTryingRecoveryCodes() throws Exception {
        when(users.findByUserName("default", "alice")).thenReturn(Optional.of(USER));
        doThrow(new IamException(IamErrorCode.MFA_CHALLENGE_LOCKED, "locked"))
                .when(totp)
                .verify(USER.id(), "123456");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mfa");
        request.setSession(pendingSession());
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.verify("123456", request, response, csrf);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(totp, org.mockito.Mockito.never()).verifyRecoveryCode(USER.id(), "123456");
    }

    @Test
    void completedAuthenticationCarriesPendingAuthorities() throws Exception {
        when(users.findByUserName("default", "alice")).thenReturn(Optional.of(USER));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mfa");
        MockHttpSession session = new MockHttpSession();
        UsernamePasswordAuthenticationToken pending =
                new UsernamePasswordAuthenticationToken(
                        "alice",
                        "n/a",
                        AuthorityUtils.createAuthorityList("ROLE_USER", "PERM_iam:read"));
        session.setAttribute(PENDING, pending);
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.verify("123456", request, response, csrf);

        List<String> authorities =
                AuthorityUtils.authorityListToSet(
                                SecurityContextHolder.getContext()
                                        .getAuthentication()
                                        .getAuthorities())
                        .stream()
                        .sorted()
                        .toList();
        assertThat(authorities).contains("ROLE_USER", "PERM_iam:read");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo("alice");
    }

    private static MockHttpSession pendingSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                PENDING,
                new UsernamePasswordAuthenticationToken(
                        "alice", "n/a", AuthorityUtils.createAuthorityList("ROLE_USER")));
        return session;
    }

    @SuppressWarnings("unused")
    private static List<GrantedAuthority> authorities() {
        return AuthorityUtils.createAuthorityList("ROLE_USER");
    }
}
