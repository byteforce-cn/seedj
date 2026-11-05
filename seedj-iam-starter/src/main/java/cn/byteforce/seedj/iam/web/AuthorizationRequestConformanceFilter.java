package cn.byteforce.seedj.iam.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 在 Spring Authorization Server 端点过滤器之前，对授权请求做 RFC 6749 / OIDC Core 的前置校验。
 *
 * <p>补齐 SAS 7.1.1 默认链中被 {@code AuthorizationFilter} 提前截断的语义：</p>
 * <ul>
 *   <li>RFC 6749 §4.1.2.1：client_id / redirect_uri 非法时 400，且绝不重定向到非法 URI；</li>
 *   <li>OIDC Core §3.1.2.6：{@code prompt=none} 时未登录返回 {@code login_required}，
 *       需要同意返回 {@code consent_required}；</li>
 *   <li>OIDC Core §3.1.2.6 / §3.1.2.1：{@code prompt=login} 与 {@code max_age} 强制重新认证；</li>
 *   <li>RFC 6749 §3.1.2.1：不支持的 {@code response_type} 以重定向错误返回
 *       {@code unsupported_response_type}；</li>
 *   <li>RFC 6749 §3.1.2.1：不支持/重复参数返回 {@code invalid_request}；</li>
 *   <li>RFC 6749 §3.3：重复或非法 {@code scope} 返回 {@code invalid_scope}；</li>
 *   <li>RFC 7636 §4.2：{@code code_challenge} 长度与字符集校验；S256 固定 43 字符。</li>
 * </ul>
 *
 * <p>强制重新认证时，把去掉 {@code prompt}/{@code max_age} 的原始授权 URL 写入短期
 * HttpOnly Cookie，登录成功处理器读取后回跳，避免重复触发。</p>
 */
public final class AuthorizationRequestConformanceFilter extends OncePerRequestFilter {

    public static final String REAUTH_COOKIE = "SEEDJ_REAUTH";

    private static final Set<String> SUPPORTED_RESPONSE_MODES = Set.of("query");
    private static final Pattern CODE_CHALLENGE_PATTERN =
            Pattern.compile("[A-Za-z0-9\\-._~]{43,128}");
    private static final Pattern SCOPE_PATTERN =
            Pattern.compile("[\\x21\\x23-\\x5B\\x5D-\\x7E]+");

    private static final String[] SINGLETON_PARAMETERS = {
            "response_type", "client_id", "redirect_uri", "scope", "state", "nonce",
            "code_challenge", "code_challenge_method", "response_mode", "prompt", "max_age",
            "login_hint", "acr_values", "claims"
    };

    private final RegisteredClientRepository registeredClients;
    private final OAuth2AuthorizationConsentService consentService;
    private final AuthorizationServerSettings settings;

    public AuthorizationRequestConformanceFilter(RegisteredClientRepository registeredClients,
                                                 OAuth2AuthorizationConsentService consentService,
                                                 AuthorizationServerSettings settings) {
        this.registeredClients = registeredClients;
        this.consentService = consentService;
        this.settings = settings;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!settings.getAuthorizationEndpoint().equals(path)) {
            return true;
        }
        if (request.getParameter("request_uri") != null) {
            // Pushed Authorization Request 由 SAS 自身处理，本过滤器不介入。
            return true;
        }
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            // POST /oauth2/authorize 是同意提交，不是授权请求。
            return request.getParameter("response_type") == null;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Map<String, String[]> params = new LinkedHashMap<>(request.getParameterMap());

        String clientId = first(params, "client_id");
        if (clientId == null || clientId.isBlank()) {
            badRequest(response, "invalid_request", "client_id is required");
            return;
        }
        RegisteredClient client = registeredClients.findByClientId(clientId);
        if (client == null) {
            badRequest(response, "invalid_client", "client_id is not registered");
            return;
        }

        String redirectUri = first(params, "redirect_uri");
        if (redirectUri == null || redirectUri.isBlank()) {
            if (client.getRedirectUris().size() == 1) {
                redirectUri = client.getRedirectUris().iterator().next();
            } else {
                badRequest(response, "invalid_request", "redirect_uri is required");
                return;
            }
        }
        if (!isRegisteredRedirectUri(client, redirectUri)) {
            badRequest(response, "invalid_request", "redirect_uri is not registered");
            return;
        }

        AuthorizationRequest authorizationRequest = new AuthorizationRequest(
                client, redirectUri, first(params, "response_type"), first(params, "state"), params);

        ValidationError error = validate(authorizationRequest);
        if (error != null) {
            redirectError(authorizationRequest, response, error);
            return;
        }

        Set<String> prompts = parsePrompts(first(params, "prompt"));
        Long maxAge = parseMaxAge(first(params, "max_age"));
        boolean authenticated = isAuthenticated();

        if (prompts.contains("none")) {
            if (!authenticated || isMaxAgeExceeded(request, maxAge)) {
                redirectError(authorizationRequest, response,
                        new ValidationError("login_required", "prompt=none requires an authenticated session"));
                return;
            }
            if (consentRequired(authorizationRequest)) {
                redirectError(authorizationRequest, response,
                        new ValidationError("consent_required", "prompt=none requires existing consent"));
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        if (prompts.contains("login") || (maxAge != null && isMaxAgeExceeded(request, maxAge))) {
            forceReauthentication(request, response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    private ValidationError validate(AuthorizationRequest request) {
        Map<String, String[]> params = request.params();

        for (String name : SINGLETON_PARAMETERS) {
            String[] values = params.get(name);
            if (values != null && values.length > 1) {
                return invalidRequest(name);
            }
        }

        String responseType = request.responseType();
        if (responseType == null || responseType.isBlank()) {
            return invalidRequest("response_type");
        }
        if (!"code".equals(responseType)) {
            return new ValidationError("unsupported_response_type", "response_type");
        }

        Set<String> scopes = requestedScopes(params);
        if (params.containsKey("scope")) {
            java.util.List<String> rawScopes = new java.util.ArrayList<>();
            for (String value : params.get("scope")) {
                for (String scope : value.split(" ")) {
                    if (!scope.isBlank()) {
                        rawScopes.add(scope);
                    }
                }
            }
            if (new java.util.HashSet<>(rawScopes).size() != rawScopes.size()) {
                return new ValidationError("invalid_scope", "scope");
            }
            for (String scope : rawScopes) {
                if (!SCOPE_PATTERN.matcher(scope).matches()) {
                    return new ValidationError("invalid_scope", "scope");
                }
            }
        }
        if (!scopes.isEmpty() && !request.client().getScopes().containsAll(scopes)) {
            return new ValidationError("invalid_scope", "scope");
        }

        ValidationError pkceError = validatePkce(request);
        if (pkceError != null) {
            return pkceError;
        }

        String[] responseModes = params.get("response_mode");
        if (responseModes != null && !SUPPORTED_RESPONSE_MODES.contains(responseModes[0])) {
            return invalidRequest("response_mode");
        }

        String[] maxAges = params.get("max_age");
        if (maxAges != null) {
            try {
                if (Long.parseLong(maxAges[0]) < 0) {
                    return invalidRequest("max_age");
                }
            } catch (NumberFormatException ex) {
                return invalidRequest("max_age");
            }
        }

        Set<String> prompts = parsePrompts(first(params, "prompt"));
        if (params.containsKey("prompt")) {
            if (prompts.isEmpty()) {
                return invalidRequest("prompt");
            }
            if (prompts.contains("none") && prompts.size() > 1) {
                return invalidRequest("prompt");
            }
        }

        return null;
    }

    private ValidationError validatePkce(AuthorizationRequest request) {
        Map<String, String[]> params = request.params();
        String[] challenges = params.get("code_challenge");
        String[] methods = params.get("code_challenge_method");
        boolean required = request.client().getClientSettings().isRequireProofKey();

        if (challenges == null) {
            if (methods != null) {
                return invalidRequest("code_challenge");
            }
            return required ? invalidRequest("code_challenge") : null;
        }
        if (challenges.length > 1) {
            return invalidRequest("code_challenge");
        }
        String challenge = challenges[0];
        if (!CODE_CHALLENGE_PATTERN.matcher(challenge).matches()) {
            return invalidRequest("code_challenge");
        }
        if (methods == null) {
            return invalidRequest("code_challenge_method");
        }
        if (methods.length > 1) {
            return invalidRequest("code_challenge_method");
        }
        if (!"S256".equals(methods[0])) {
            return invalidRequest("code_challenge_method");
        }
        // RFC 7636 §4.2：S256 的 code_challenge 是 32 字节 SHA-256 的 base64url，固定 43 字符。
        if (challenge.length() != 43) {
            return invalidRequest("code_challenge");
        }
        return null;
    }

    private static Set<String> requestedScopes(Map<String, String[]> params) {
        Set<String> scopes = new LinkedHashSet<>();
        String[] values = params.get("scope");
        if (values == null) {
            return scopes;
        }
        for (String value : values) {
            for (String scope : value.split(" ")) {
                if (!scope.isBlank()) {
                    scopes.add(scope);
                }
            }
        }
        return scopes;
    }

    private boolean isRegisteredRedirectUri(RegisteredClient client, String redirectUri) {
        if (!client.getRedirectUris().contains(redirectUri)) {
            return false;
        }
        try {
            URI uri = URI.create(redirectUri);
            return uri.isAbsolute() && uri.getFragment() == null;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean consentRequired(AuthorizationRequest request) {
        if (!request.client().getClientSettings().isRequireAuthorizationConsent()) {
            return false;
        }
        Set<String> requested = requestedScopes(request.params());
        requested.remove("openid");
        if (requested.isEmpty()) {
            return false;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return true;
        }
        OAuth2AuthorizationConsent consent =
                consentService.findById(request.client().getId(), authentication.getName());
        return consent == null || !consent.getScopes().containsAll(requested);
    }

    private static boolean isMaxAgeExceeded(HttpServletRequest request, Long maxAge) {
        if (maxAge == null) {
            return false;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            return true;
        }
        long ageMillis = System.currentTimeMillis() - session.getCreationTime();
        if (maxAge > Long.MAX_VALUE / 1000L) {
            return false;
        }
        return ageMillis > (maxAge * 1000L);
    }

    private static boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private static Set<String> parsePrompts(String value) {
        Set<String> prompts = new LinkedHashSet<>();
        if (value == null) {
            return prompts;
        }
        for (String part : value.split(" ")) {
            if (!part.isBlank()) {
                prompts.add(part);
            }
        }
        return prompts;
    }

    private static Long parseMaxAge(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String first(Map<String, String[]> params, String name) {
        String[] values = params.get(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    private static ValidationError invalidRequest(String parameter) {
        return new ValidationError("invalid_request", "OAuth 2.0 Parameter: " + parameter);
    }

    // ------------------------------------------------------------------
    // Responses
    // ------------------------------------------------------------------

    private void redirectError(AuthorizationRequest request, HttpServletResponse response,
                               ValidationError error) throws IOException {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(request.redirectUri())
                .queryParam("error", error.error());
        if (error.description() != null) {
            builder.queryParam("error_description", error.description());
        }
        if (request.state() != null) {
            builder.queryParam("state", request.state());
        }
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader(HttpHeaders.LOCATION, builder.build().encode().toUriString());
    }

    private static void badRequest(HttpServletResponse response, String error, String description)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + jsonEscape(error)
                + "\",\"error_description\":\"" + jsonEscape(description) + "\"}");
    }

    private void forceReauthentication(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String target = reauthenticationTarget(request);
        ResponseCookie cookie = ResponseCookie.from(REAUTH_COOKIE,
                        URLEncoder.encode(target, StandardCharsets.UTF_8))
                .httpOnly(true)
                .secure(request.isSecure())
                .path("/")
                .maxAge(Duration.ofMinutes(5))
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            new SecurityContextLogoutHandler().logout(request, response, authentication);
        }

        String requestUrl = request.getRequestURL().toString();
        int pathIndex = requestUrl.indexOf(request.getRequestURI());
        String origin = pathIndex >= 0 ? requestUrl.substring(0, pathIndex) : requestUrl;
        response.sendRedirect(origin + request.getContextPath() + "/login");
    }

    private static String reauthenticationTarget(HttpServletRequest request) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(request.getRequestURL().toString());
        request.getParameterMap().forEach((name, values) -> {
            if ("prompt".equals(name) || "max_age".equals(name) || "continue".equals(name)) {
                return;
            }
            for (String value : values) {
                builder.queryParam(name, value);
            }
        });
        return builder.build().encode().toUriString();
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record AuthorizationRequest(RegisteredClient client,
                                        String redirectUri,
                                        String responseType,
                                        String state,
                                        Map<String, String[]> params) {
    }

    private record ValidationError(String error, String description) {
    }
}
