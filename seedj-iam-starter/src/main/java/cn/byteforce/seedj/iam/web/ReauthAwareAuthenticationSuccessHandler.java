package cn.byteforce.seedj.iam.web;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 登录成功处理器：优先消费 {@link AuthorizationRequestConformanceFilter#REAUTH_COOKIE}，
 * 在强制重新认证（{@code prompt=login} / {@code max_age}）后回到去掉 prompt/max_age 的
 * 原授权请求；否则回退到 Spring Security 的 SavedRequest 行为。
 */
public final class ReauthAwareAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final SavedRequestAwareAuthenticationSuccessHandler delegate =
            new SavedRequestAwareAuthenticationSuccessHandler();

    private final AuthorizationServerSettings settings;

    public ReauthAwareAuthenticationSuccessHandler(AuthorizationServerSettings settings) {
        this.settings = settings;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        String encodedTarget = readCookie(request, AuthorizationRequestConformanceFilter.REAUTH_COOKIE);
        if (encodedTarget != null) {
            deleteCookie(request, response);
            String target = URLDecoder.decode(encodedTarget, StandardCharsets.UTF_8);
            if (isSameOriginAuthorizationRequest(request, target)) {
                response.setStatus(HttpServletResponse.SC_FOUND);
                response.setHeader(HttpHeaders.LOCATION, target);
                return;
            }
        }
        delegate.onAuthenticationSuccess(request, response, authentication);
    }

    private boolean isSameOriginAuthorizationRequest(HttpServletRequest request, String target) {
        try {
            URI targetUri = URI.create(target);
            URI requestUri = URI.create(request.getRequestURL().toString());
            return targetUri.getScheme() != null
                    && targetUri.getScheme().equalsIgnoreCase(requestUri.getScheme())
                    && targetUri.getHost() != null
                    && targetUri.getHost().equalsIgnoreCase(requestUri.getHost())
                    && targetUri.getPort() == requestUri.getPort()
                    && (request.getContextPath() + settings.getAuthorizationEndpoint())
                            .equals(targetUri.getPath());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static void deleteCookie(HttpServletRequest request, HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(AuthorizationRequestConformanceFilter.REAUTH_COOKIE, "")
                .httpOnly(true)
                .secure(request.isSecure())
                .path("/")
                .maxAge(Duration.ZERO)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
