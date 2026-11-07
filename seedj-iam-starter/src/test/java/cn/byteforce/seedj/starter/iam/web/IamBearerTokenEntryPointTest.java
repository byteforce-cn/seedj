package cn.byteforce.seedj.starter.iam.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;

/** RFC 6750 §3.1：ID Token 被当作 Bearer 使用时返回 invalid_token，且不缓存错误。 */
class IamBearerTokenEntryPointTest {

    private final IamBearerTokenEntryPoint entryPoint = new IamBearerTokenEntryPoint();

    @Test
    void idTokenPresentedAsAccessTokenIsInvalidToken() throws Exception {
        MockHttpServletRequest request = bearer(jwt("{\"alg\":\"RS256\",\"typ\":\"JWT\"}"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(
                request, response, new InsufficientAuthenticationException("no access token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .contains("invalid_token")
                .contains("invalid access token");
        assertThat(response.getContentAsString())
                .contains("\"error\":\"invalid_token\"")
                .contains("invalid access token");
    }

    @Test
    void accessTokenWithAtJwtTypeIsDelegated() throws Exception {
        MockHttpServletRequest request = bearer(jwt("{\"alg\":\"RS256\",\"typ\":\"at+jwt\"}"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException("bad token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(response.getContentAsString()).doesNotContain("invalid access token");
    }

    @Test
    void opaqueBearerTokenIsDelegated() throws Exception {
        MockHttpServletRequest request = bearer("not-a-jwt-without-dots");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException("bad token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).doesNotContain("invalid access token");
    }

    @Test
    void unparsableJwtIsDelegated() throws Exception {
        MockHttpServletRequest request = bearer("a.b.c");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException("bad token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).doesNotContain("invalid access token");
    }

    @Test
    void basicAuthenticationIsDelegated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic d2ViOnNlY3JldA==");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new InsufficientAuthenticationException("no token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).doesNotContain("invalid access token");
    }

    @Test
    void missingAuthorizationHeaderIsDelegated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new InsufficientAuthenticationException("no token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).doesNotContain("invalid access token");
    }

    private static MockHttpServletRequest bearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return request;
    }

    private static String jwt(String headerJson) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8))
                + "."
                + encoder.encodeToString("{\"sub\":\"alice\"}".getBytes(StandardCharsets.UTF_8))
                + "."
                + encoder.encodeToString("signature".getBytes(StandardCharsets.UTF_8));
    }
}
