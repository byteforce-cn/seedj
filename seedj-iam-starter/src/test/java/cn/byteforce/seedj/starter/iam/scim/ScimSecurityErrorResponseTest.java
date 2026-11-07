package cn.byteforce.seedj.starter.iam.scim;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;

class ScimSecurityErrorResponseTest {

    @Test
    void authenticationEntryPointWritesScimErrorEnvelope() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ScimAuthenticationEntryPoint().commence(new MockHttpServletRequest(), response,
                new BadCredentialsException("missing token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer error=\"invalid_token\"");
        assertThat(response.getContentType()).isEqualTo(ScimSupport.CONTENT_TYPE);
        String body = response.getContentAsString();
        assertThat(body).contains("\"status\":\"401\"")
                .contains("invalid or missing bearer token")
                .contains(ScimSupport.ERROR_SCHEMA)
                .doesNotContain("scimType");
    }

    @Test
    void accessDeniedHandlerWritesInsufficientScopeEnvelope() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ScimAccessDeniedHandler().handle(new MockHttpServletRequest(), response,
                new AccessDeniedException("insufficient scope"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer error=\"insufficient_scope\"");
        assertThat(response.getContentType()).isEqualTo(ScimSupport.CONTENT_TYPE);
        String body = response.getContentAsString();
        assertThat(body).contains("\"status\":\"403\"")
                .contains("insufficient scope")
                .doesNotContain("scimType");
    }
}
