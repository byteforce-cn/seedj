package cn.byteforce.seedj.starter.resource;

import cn.byteforce.seedj.core.context.RequestContext;
import cn.byteforce.seedj.web.core.ApiResponse;
import cn.byteforce.seedj.web.core.ResponseCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * 401 响应器：未认证请求输出统一错误体（{@link ApiResponse}），并保留
 * {@code WWW-Authenticate: Bearer} 协议头。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class ApiResponseAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final JsonMapper jsonMapper;

    /**
     * 构造响应器。
     *
     * @param jsonMapper JSON 序列化器（沿用 seedj JSON 契约）
     */
    public ApiResponseAuthenticationEntryPoint(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        ResponseWriter.write(jsonMapper, response,
                ApiResponse.error(ResponseCode.UNAUTHORIZED.getCode(),
                        ResponseCode.UNAUTHORIZED.getMessage(),
                        RequestContext.traceId().orElse(null)));
    }
}
