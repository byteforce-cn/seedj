package cn.byteforce.seedj.iam.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * token / introspection / revocation 端点的参数级 RFC 兜底：
 *
 * <ul>
 *   <li>RFC 6749 §3.2 / RFC 7662 §2.1 / RFC 7009 §2.1：请求参数不得重复；
 *       SAS 7.1.1 对重复参数会抛未处理异常（500），这里统一返回 400 invalid_request。</li>
 *   <li>RFC 6749 §5.2：token 端点没有携带任何客户端认证信息时返回 invalid_client，
 *       避免落到 grant provider 后变成 invalid_request。</li>
 * </ul>
 *
 * <p>{@code jakarta.servlet.request.X509Certificate} 只表示 TLS 层完成了双向认证，
 * 不等价于 OAuth 2.0 客户端认证：SAS 的 X509 转换器仍要求请求携带 {@code client_id}
 * 并由 {@code RegisteredClient} 声明 {@code tls_client_auth}。因此本过滤器不把
 * “存在客户端证书”当作客户端认证信息，否则会跳过 missing-client 的 invalid_client
 * 兜底，让空客户端请求落到 SAS 的 invalid_request。</p>
 */
public final class EndpointParameterConformanceFilter extends OncePerRequestFilter {

    private final AuthorizationServerSettings settings;

    public EndpointParameterConformanceFilter(AuthorizationServerSettings settings) {
        this.settings = settings;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(settings.getTokenEndpoint().equals(path)
                || settings.getTokenIntrospectionEndpoint().equals(path)
                || settings.getTokenRevocationEndpoint().equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            if (entry.getValue().length > 1) {
                badRequest(response, "invalid_request", "duplicate parameter: " + entry.getKey());
                return;
            }
        }

        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (settings.getTokenEndpoint().equals(path) && !hasClientAuthentication(request)) {
            badRequest(response, "invalid_client", "client authentication is required");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static boolean hasClientAuthentication(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return true;
        }
        if (request.getParameter("client_id") != null) {
            return true;
        }
        return request.getParameter("client_assertion") != null;
    }

    private static void badRequest(HttpServletResponse response, String error, String description)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + jsonEscape(error)
                + "\",\"error_description\":\"" + jsonEscape(description) + "\"}");
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
