package cn.byteforce.seedj.starter.iam.nativeauth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2ErrorAuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import tools.jackson.databind.json.JsonMapper;

/** Adds mfa_token/mfa_methods to the standard OAuth2 error response. */
public class NativeAuthErrorResponseHandler implements AuthenticationFailureHandler {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OAuth2ErrorAuthenticationFailureHandler delegate =
            new OAuth2ErrorAuthenticationFailureHandler();

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception)
            throws IOException, ServletException {
        if (exception instanceof NativeAuthMfaRequiredException mfa) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "mfa_required");
            body.put("error_description", "multi-factor authentication is required");
            body.putAll(mfa.parameters());
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
            JSON.writeValue(response.getOutputStream(), body);
            return;
        }
        delegate.onAuthenticationFailure(request, response, exception);
    }
}
