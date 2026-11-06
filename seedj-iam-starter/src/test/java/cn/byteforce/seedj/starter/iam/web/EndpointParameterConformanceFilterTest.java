package cn.byteforce.seedj.starter.iam.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** RFC 6749 §3.2 / RFC 7662 / RFC 7009 参数级兜底的行为测试。 */
class EndpointParameterConformanceFilterTest {

    private final AtomicBoolean chainInvoked = new AtomicBoolean();
    private EndpointParameterConformanceFilter filter;

    @BeforeEach
    void setUp() {
        chainInvoked.set(false);
        filter = new EndpointParameterConformanceFilter(
                AuthorizationServerSettings.builder().issuer("https://iam.test").build());
    }

    @Test
    void ignoresNonOAuthEndpoints() throws Exception {
        MockHttpServletResponse response = run(request("/oauth2/authorize"));

        assertThat(chainInvoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void duplicateTokenParameterIsBadRequest() throws Exception {
        MockHttpServletResponse response = run(request("/oauth2/token",
                "grant_type", "authorization_code", "grant_type", "refresh_token"));

        assertJsonError(response, "invalid_request");
        assertThat(response.getContentAsString()).contains("duplicate parameter: grant_type");
    }

    @Test
    void duplicateIntrospectionParameterIsBadRequest() throws Exception {
        MockHttpServletResponse response = run(request("/oauth2/introspect",
                "token", "a", "token", "b"));

        assertJsonError(response, "invalid_request");
        assertThat(response.getContentAsString()).contains("duplicate parameter: token");
    }

    @Test
    void duplicateRevocationParameterIsBadRequest() throws Exception {
        MockHttpServletResponse response = run(request("/oauth2/revoke",
                "token", "a", "token", "b"));

        assertJsonError(response, "invalid_request");
    }

    @Test
    void tokenWithoutClientAuthenticationIsInvalidClient() throws Exception {
        MockHttpServletResponse response = run(request("/oauth2/token", "grant_type", "client_credentials"));

        assertJsonError(response, "invalid_client");
        assertThat(response.getContentAsString()).contains("client authentication is required");
    }

    @Test
    void clientCertificateAloneIsNotClientAuthentication() throws Exception {
        MockHttpServletRequest request = request("/oauth2/token", "grant_type", "client_credentials",
                "client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        request.setAttribute("jakarta.servlet.request.X509Certificate",
                new X509Certificate[] {mock(X509Certificate.class)});

        MockHttpServletResponse response = run(request);

        assertJsonError(response, "invalid_client");
    }

    @Test
    void basicAuthenticationIsAccepted() throws Exception {
        MockHttpServletRequest request = request("/oauth2/token", "grant_type", "client_credentials");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic d2ViOnNlY3JldA==");

        run(request);

        assertThat(chainInvoked).isTrue();
    }

    @Test
    void clientIdParameterIsAccepted() throws Exception {
        run(request("/oauth2/token", "grant_type", "authorization_code", "client_id", "web"));

        assertThat(chainInvoked).isTrue();
    }

    @Test
    void clientAssertionParameterIsAccepted() throws Exception {
        run(request("/oauth2/token", "grant_type", "authorization_code", "client_assertion", "jwt"));

        assertThat(chainInvoked).isTrue();
    }

    @Test
    void introspectionWithoutClientAuthenticationIsPassedThrough() throws Exception {
        run(request("/oauth2/introspect", "token", "a"));

        assertThat(chainInvoked).isTrue();
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> chainInvoked.set(true);
        filter.doFilter(request, response, chain);
        return response;
    }

    private static MockHttpServletRequest request(String path, String... nameValues) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        for (int i = 0; i + 1 < nameValues.length; i += 2) {
            request.addParameter(nameValues[i], nameValues[i + 1]);
        }
        return request;
    }

    private static void assertJsonError(MockHttpServletResponse response, String error)
            throws Exception {
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(response.getContentAsString()).contains("\"error\":\"" + error + "\"");
    }
}
