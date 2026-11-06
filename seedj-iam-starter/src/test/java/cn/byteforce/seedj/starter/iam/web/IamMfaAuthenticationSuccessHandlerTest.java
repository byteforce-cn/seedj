package cn.byteforce.seedj.starter.iam.web;

import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.starter.iam.identity.IamTotpService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 已启用 TOTP 的用户在密码通过后必须停在 /mfa，不得直接建立完整会话。 */
class IamMfaAuthenticationSuccessHandlerTest {

    private static final IamUser USER = IamUser.create("user-1", "default", "alice", "Alice",
            "alice@example.com", true);

    private final IamUserRepository users = mock(IamUserRepository.class);
    private final IamTotpService totp = mock(IamTotpService.class);
    private IamMfaAuthenticationSuccessHandler handler;

    @BeforeEach
    void setUp() {
        handler = new IamMfaAuthenticationSuccessHandler(users, totp, "default",
                AuthorizationServerSettings.builder().issuer("https://iam.test").build());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void userWithTotpStaysPendingAndRedirectsToChallenge() throws Exception {
        when(users.findByUserName("default", "alice")).thenReturn(Optional.of(USER));
        when(totp.isEnabled(USER.id())).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("alice", "n/a");

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo("/mfa");
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(request.getSession(false)).isNotNull();
        assertThat(request.getSession(false)
                .getAttribute(IamMfaAuthenticationSuccessHandler.PENDING_ATTRIBUTE))
                .isSameAs(authentication);
        assertThat(request.getSession(false)
                .getAttribute("SPRING_SECURITY_SAVED_REQUEST")).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void userWithoutTotpCompletesLogin() throws Exception {
        when(users.findByUserName("default", "alice")).thenReturn(Optional.of(USER));
        when(totp.isEnabled(USER.id())).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response,
                new UsernamePasswordAuthenticationToken("alice", "n/a"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void unknownUserCompletesLogin() throws Exception {
        when(users.findByUserName("default", "alice")).thenReturn(Optional.empty());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response,
                new UsernamePasswordAuthenticationToken("alice", "n/a"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void missingTotpServiceCompletesLogin() throws Exception {
        IamMfaAuthenticationSuccessHandler withoutTotp = new IamMfaAuthenticationSuccessHandler(
                users, null, "default",
                AuthorizationServerSettings.builder().issuer("https://iam.test").build());
        when(users.findByUserName("default", "alice")).thenReturn(Optional.of(USER));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        withoutTotp.onAuthenticationSuccess(request, response,
                new UsernamePasswordAuthenticationToken("alice", "n/a"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/");
    }
}
