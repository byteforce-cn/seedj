package cn.byteforce.seedj.starter.iam.web;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 强制重新认证后回跳的白名单校验：只回到同源授权端点。 */
class ReauthAwareAuthenticationSuccessHandlerTest {

    private static final String TARGET = "http://localhost/oauth2/authorize?client_id=web&state=s1";

    private ReauthAwareAuthenticationSuccessHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ReauthAwareAuthenticationSuccessHandler(
                AuthorizationServerSettings.builder().issuer("https://iam.test").build());
    }

    @Test
    void redirectsToSameOriginAuthorizationRequestAndClearsCookie() throws Exception {
        MockHttpServletRequest request = loginRequest(TARGET);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication());

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo(TARGET);
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .contains("SEEDJ_REAUTH=")
                .contains("Max-Age=0");
    }

    @Test
    void honorsContextPathInTarget() throws Exception {
        String target = "http://localhost/iam/oauth2/authorize?client_id=web";
        MockHttpServletRequest request = loginRequest(target);
        request.setContextPath("/iam");
        request.setRequestURI("/iam/oauth2/authorize");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication());

        assertThat(response.getRedirectedUrl()).isEqualTo(target);
    }

    @Test
    void rejectsForeignOrigin() throws Exception {
        MockHttpServletRequest request = loginRequest("https://evil.example/oauth2/authorize?client_id=web");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication());

        assertThat(response.getRedirectedUrl()).isNotEqualTo("https://evil.example/oauth2/authorize?client_id=web");
    }

    @Test
    void rejectsSameOriginButDifferentPath() throws Exception {
        MockHttpServletRequest request = loginRequest("http://localhost/admin/secrets");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication());

        assertThat(response.getRedirectedUrl()).isNotEqualTo("http://localhost/admin/secrets");
    }

    @Test
    void rejectsPortMismatch() throws Exception {
        MockHttpServletRequest request = loginRequest("http://localhost:8080/oauth2/authorize?client_id=web");
        request.setServerPort(80);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication());

        assertThat(response.getRedirectedUrl()).isNotEqualTo("http://localhost:8080/oauth2/authorize?client_id=web");
    }

    @Test
    void rejectsMalformedTarget() throws Exception {
        MockHttpServletRequest request = loginRequest("::::");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication());

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void fallsBackToSavedRequestWithoutCookie() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication());

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
    }

    private static MockHttpServletRequest loginRequest(String target) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setCookies(new Cookie(AuthorizationRequestConformanceFilter.REAUTH_COOKIE,
                URLEncoder.encode(target, StandardCharsets.UTF_8)));
        return request;
    }

    private static UsernamePasswordAuthenticationToken authentication() {
        return new UsernamePasswordAuthenticationToken("alice", "n/a");
    }
}
