package cn.byteforce.seedj.biz.security;

import cn.byteforce.seedj.core.security.SecurityErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 401 响应体：与 {@code IamExceptionHandler} 同形（{@code {error, message}}），
 * 前端只写一套解析。
 *
 * <p>保留 {@code WWW-Authenticate: Bearer} 头：OAuth2 资源服务器要求 401 带该头，
 * Spring Security 的默认实现也会加。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /**
     * 复用 Spring 的实现写 {@code WWW-Authenticate}：RFC 6750 要求无效令牌带
     * {@code error="invalid_token"}，手写容易漏掉，IT 也会因此失败。
     */
    private final BearerTokenAuthenticationEntryPoint headerWriter =
            new BearerTokenAuthenticationEntryPoint();

    private final JsonMapper json;
    private final SecurityDenialAudit audit;

    public JsonAuthenticationEntryPoint(JsonMapper json, SecurityDenialAudit audit) {
        this.json = json;
        this.audit = audit;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        SecurityErrorCode code = authException instanceof InsufficientAuthenticationException
                ? SecurityErrorCode.AUTHENTICATION_REQUIRED
                : SecurityErrorCode.AUTHENTICATION_FAILED;
        headerWriter.commence(request, response, authException);
        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(response.getOutputStream(), Map.of(
                "error", code.name(),
                "message", code == SecurityErrorCode.AUTHENTICATION_REQUIRED
                        ? "authentication required" : "invalid credentials"));
        audit.recordDenial(request, code);
    }
}
