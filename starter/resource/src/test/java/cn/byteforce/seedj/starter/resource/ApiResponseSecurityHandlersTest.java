package cn.byteforce.seedj.starter.resource;

import cn.byteforce.seedj.core.context.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 401/403 响应器单元测试：状态码、协议头、统一错误体与 traceId 透传。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class ApiResponseSecurityHandlersTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void entryPointWrites401UnifiedBodyWithBearerChallenge() throws Exception {
        RequestContext.setTraceId("trace-401");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ApiResponseAuthenticationEntryPoint(jsonMapper).commence(
                new MockHttpServletRequest(), response, new InsufficientAuthenticationException("no token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(response.getContentType()).startsWith("application/json");
        String body = response.getContentAsString();
        assertThat(body).contains("\"code\":10002").contains("\"traceId\":\"trace-401\"");
    }

    @Test
    void accessDeniedHandlerWrites403UnifiedBody() throws Exception {
        RequestContext.setTraceId("trace-403");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ApiResponseAccessDeniedHandler(jsonMapper).handle(
                new MockHttpServletRequest(), response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        String body = response.getContentAsString();
        assertThat(body).contains("\"code\":10003").contains("\"traceId\":\"trace-403\"");
    }
}
