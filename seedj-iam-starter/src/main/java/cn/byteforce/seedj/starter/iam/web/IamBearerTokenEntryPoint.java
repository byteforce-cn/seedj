package cn.byteforce.seedj.starter.iam.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import com.nimbusds.jwt.SignedJWT;

import java.io.IOException;

/** RFC 6750 JSON error body for protected IAM/OIDC endpoints. */
public class IamBearerTokenEntryPoint implements AuthenticationEntryPoint {

    private final BearerTokenAuthenticationEntryPoint delegate =
            new BearerTokenAuthenticationEntryPoint();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        if (isIdTokenPresented(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                    "Bearer error=\"invalid_token\", error_description=\"invalid access token\"");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader("Pragma", "no-cache");
            response.getWriter().write(
                    "{\"error\":\"invalid_token\",\"error_description\":\"invalid access token\"}");
            return;
        }
        delegate.commence(request, response, authException);
    }

    private static boolean isIdTokenPresented(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        String token = authorization.substring(7).trim();
        if (token.chars().filter(ch -> ch == '.').count() != 2) {
            return false;
        }
        try {
            String type = SignedJWT.parse(token).getHeader().getType() == null ? ""
                    : SignedJWT.parse(token).getHeader().getType().toString();
            return !"at+jwt".equals(type);
        } catch (Exception ignored) {
            return false;
        }
    }
}
