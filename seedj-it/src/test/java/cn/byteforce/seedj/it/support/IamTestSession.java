package cn.byteforce.seedj.it.support;

import com.jayway.jsonpath.JsonPath;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * IAM 集成测试的会话级客户端。
 *
 * <p>封装 RFC 6749 / OIDC Core 授权码流程所需的浏览器语义：Cookie 会话、登录页 CSRF、 同意页、授权码回跳、token 交换与刷新。每个实例持有独立的
 * {@link CookieManager}， 因此测试之间不会共享登录态或同意态。
 *
 * <p>这个类只存在于测试代码中，不进入 starter 的发布产物。
 */
public final class IamTestSession {

    public static final String CLIENT_ID = "default-client";
    public static final String CLIENT_SECRET = "secret";
    public static final String REDIRECT_URI =
            "http://127.0.0.1:8080/login/oauth2/code/default-client";

    /** RFC 8705 mTLS 客户端：认证方式为 tls_client_auth，无 client_secret。 */
    public static final String TLS_CLIENT_ID = "tls-client";

    public static final String TLS_REDIRECT_URI =
            "http://127.0.0.1:8080/login/oauth2/code/tls-client";

    /** RFC 7636 Appendix B 的示例 verifier / challenge 对。 */
    public static final String CODE_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

    public static final String CODE_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    private static final Pattern CSRF_PATTERN =
            Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]*)\"");

    private final String issuer;
    private final URI issuerUri;
    private final RestTestClient client;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    /** 默认 client_secret_basic 客户端。 */
    public IamTestSession(String issuer, SSLContext sslContext) {
        this(issuer, sslContext, CLIENT_ID, CLIENT_SECRET, REDIRECT_URI);
    }

    /** RFC 8705 tls_client_auth 客户端（无 client_secret）。 */
    public IamTestSession(String issuer, SSLContext sslContext, String clientId) {
        this(issuer, sslContext, clientId, null, TLS_REDIRECT_URI);
    }

    private IamTestSession(
            String issuer,
            SSLContext sslContext,
            String clientId,
            String clientSecret,
            String redirectUri) {
        this.issuer = issuer;
        this.issuerUri = URI.create(issuer);
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;

        HttpClient httpClient =
                HttpClient.newBuilder()
                        .sslContext(sslContext)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .build();

        this.client =
                RestTestClient.bindToServer(new JdkClientHttpRequestFactory(httpClient))
                        .baseUrl(issuer)
                        .build();
    }

    public String issuer() {
        return issuer;
    }

    public URI issuerUri() {
        return issuerUri;
    }

    public RestTestClient client() {
        return client;
    }

    public static String fetchIssuer(RestTestClient iam) {
        String body =
                iam.get()
                        .uri("/.well-known/openid-configuration")
                        .accept(MediaType.APPLICATION_JSON)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(String.class)
                        .returnResult()
                        .getResponseBody();
        return JsonPath.read(body, "$.issuer");
    }

    // ------------------------------------------------------------------
    // Authorization endpoint
    // ------------------------------------------------------------------

    /** 驱动一次成功授权：登录（如需要）、同意（如需要）、返回授权码。 */
    public AuthorizationResult authorize(
            String scope,
            String state,
            String nonce,
            Map<String, String> extra,
            String username,
            String password) {
        return authorize(scope, state, nonce, extra, username, password, null);
    }

    public AuthorizationResult authorize(
            String scope,
            String state,
            String nonce,
            Map<String, String> extra,
            String username,
            String password,
            List<String> approvedScopes) {
        URI authorizationUri = authorizationUri(scope, state, nonce, extra);
        HttpResult result = get(authorizationUri, MediaType.TEXT_HTML);

        if (isLoginRedirect(result.location())) {
            HttpResult login = login(username, password);
            if (login.location() == null) {
                throw new IllegalStateException("登录后没有回到被保存的授权请求: " + login);
            }
            result = get(URI.create(login.location()), MediaType.TEXT_HTML);
        }

        if (result.status() == 200) {
            result = submitConsent(result, approvedScopes);
        }

        if (result.status() != 302) {
            throw new IllegalStateException(
                    "授权端点未返回 302: " + result.status() + " body=" + result.body());
        }
        String location = result.location();
        if (location == null) {
            throw new IllegalStateException("授权端点 302 缺少 Location");
        }
        String code = queryParam(location, "code");
        if (code == null) {
            throw new IllegalStateException("授权成功但缺少 code: " + location);
        }
        return new AuthorizationResult(code, queryParam(location, "state"), URI.create(location));
    }

    /** 发送原始授权请求，不做登录/同意处理。用于负向与 prompt 语义断言。 */
    public HttpResult authorizationRequest(List<String[]> params, MediaType accept) {
        return get(authorizationUri(params), accept);
    }

    public HttpResult authorizationRequest(Map<String, String> params, MediaType accept) {
        List<String[]> list = new ArrayList<>();
        params.forEach((k, v) -> list.add(new String[] {k, v}));
        return authorizationRequest(list, accept);
    }

    public URI authorizationUri(
            String scope, String state, String nonce, Map<String, String> extra) {
        List<String[]> params = baseAuthorizationParams(scope, state, nonce);
        if (extra != null) {
            extra.forEach((k, v) -> params.add(new String[] {k, v}));
        }
        return authorizationUri(params);
    }

    public List<String[]> baseAuthorizationParams(String scope, String state, String nonce) {
        List<String[]> params = new ArrayList<>();
        params.add(new String[] {"response_type", "code"});
        params.add(new String[] {"client_id", clientId});
        params.add(new String[] {"redirect_uri", redirectUri});
        if (scope != null) {
            params.add(new String[] {"scope", scope});
        }
        if (state != null) {
            params.add(new String[] {"state", state});
        }
        if (nonce != null) {
            params.add(new String[] {"nonce", nonce});
        }
        params.add(new String[] {"code_challenge", CODE_CHALLENGE});
        params.add(new String[] {"code_challenge_method", "S256"});
        return params;
    }

    private URI authorizationUri(List<String[]> params) {
        UriComponentsBuilder builder =
                UriComponentsBuilder.fromUriString(issuer + "/oauth2/authorize");
        params.forEach(param -> builder.queryParam(param[0], param[1]));
        return builder.build().encode().toUri();
    }

    /** 提交同意页。传入 {@code approvedScopes == null} 表示同意页上的全部作用域； 传入空集合表示拒绝（模拟取消）。 */
    public HttpResult submitConsent(HttpResult consentPage, List<String> approvedScopes) {
        String body = consentPage.body();
        if (body == null) {
            throw new IllegalStateException("同意页没有响应体");
        }
        String state = extract(body, "state");
        String clientId = extract(body, "client_id");
        if (state == null || clientId == null) {
            throw new IllegalStateException("同意页缺少 state/client_id: " + body);
        }
        List<String> pageScopes = extractAll(body, "scope");
        List<String> scopes = approvedScopes == null ? pageScopes : approvedScopes;

        List<String[]> params = new ArrayList<>();
        params.add(new String[] {"client_id", clientId});
        params.add(new String[] {"state", state});
        params.add(new String[] {"consent_action", "approve"});
        scopes.forEach(scope -> params.add(new String[] {"scope", scope}));

        return postForm("/oauth2/authorize", form(params), MediaType.TEXT_HTML, null, null);
    }

    public HttpResult login(String username, String password) {
        HttpResult loginPage = get("/login", MediaType.TEXT_HTML);
        if (loginPage.status() != 200) {
            throw new IllegalStateException("登录页不可用: " + loginPage.status());
        }
        String csrf = extract(loginPage.body(), "_csrf");
        if (csrf == null) {
            throw new IllegalStateException("登录页缺少 CSRF token");
        }
        HttpResult result =
                postForm(
                        "/login",
                        form(
                                List.of(
                                        new String[] {"username", username},
                                        new String[] {"password", password},
                                        new String[] {"_csrf", csrf})),
                        MediaType.TEXT_HTML,
                        null,
                        null);
        if (result.status() != 302) {
            throw new IllegalStateException("登录失败: " + result.status() + " body=" + result.body());
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Token endpoint
    // ------------------------------------------------------------------

    public TokenResponse exchangeCode(String code) {
        HttpResult result =
                tokenRequest(
                        form(
                                List.of(
                                        new String[] {"grant_type", "authorization_code"},
                                        new String[] {"code", code},
                                        new String[] {"redirect_uri", redirectUri},
                                        new String[] {"code_verifier", CODE_VERIFIER})));
        if (result.status() != 200) {
            throw new IllegalStateException(
                    "token 交换失败: " + result.status() + " body=" + result.body());
        }
        return TokenResponse.parse(result.body());
    }

    public HttpResult tokenRequest(String formBody) {
        if (clientSecret == null) {
            // RFC 8705：证书认证仍需要 client_id 用于定位 RegisteredClient。
            return postForm(
                    "/oauth2/token",
                    formBody + "&client_id=" + urlEncode(clientId),
                    MediaType.APPLICATION_JSON,
                    null,
                    null);
        }
        return postForm(
                "/oauth2/token", formBody, MediaType.APPLICATION_JSON, clientId, clientSecret);
    }

    public HttpResult tokenRequestWithoutClient(String formBody) {
        return postForm("/oauth2/token", formBody, MediaType.APPLICATION_JSON, null, null);
    }

    public HttpResult tokenRequestWithBasic(String clientId, String clientSecret, String formBody) {
        return postForm(
                "/oauth2/token", formBody, MediaType.APPLICATION_JSON, clientId, clientSecret);
    }

    public HttpResult tokenRequestWithPostClient(
            String formBody, String clientId, String clientSecret) {
        String body =
                formBody
                        + "&client_id="
                        + urlEncode(clientId)
                        + "&client_secret="
                        + urlEncode(clientSecret);
        return postForm("/oauth2/token", body, MediaType.APPLICATION_JSON, null, null);
    }

    public TokenResponse refresh(String refreshToken) {
        HttpResult result =
                tokenRequest(
                        form(
                                List.of(
                                        new String[] {"grant_type", "refresh_token"},
                                        new String[] {"refresh_token", refreshToken})));
        if (result.status() != 200) {
            throw new IllegalStateException(
                    "refresh 失败: " + result.status() + " body=" + result.body());
        }
        return TokenResponse.parse(result.body());
    }

    public HttpResult refreshRequest(String refreshToken) {
        return tokenRequest(
                form(
                        List.of(
                                new String[] {"grant_type", "refresh_token"},
                                new String[] {"refresh_token", refreshToken})));
    }

    // ------------------------------------------------------------------
    // UserInfo / introspection / revocation / logout
    // ------------------------------------------------------------------

    public HttpResult userInfo(String accessToken) {
        return get("/userinfo", MediaType.APPLICATION_JSON, accessToken);
    }

    public HttpResult userInfoPost(String accessToken) {
        var spec =
                client.post()
                        .uri("/userinfo")
                        .accept(MediaType.APPLICATION_JSON)
                        .headers(headers -> headers.setBearerAuth(accessToken))
                        .exchange()
                        .returnResult(String.class);
        return new HttpResult(
                spec.getStatus().value(), spec.getResponseHeaders(), spec.getResponseBody());
    }

    public HttpResult introspect(String token) {
        return postForm(
                "/oauth2/introspect",
                form("token", token),
                MediaType.APPLICATION_JSON,
                CLIENT_ID,
                CLIENT_SECRET);
    }

    public HttpResult introspectWithBasic(String token, String clientId, String clientSecret) {
        return postForm(
                "/oauth2/introspect",
                form("token", token),
                MediaType.APPLICATION_JSON,
                clientId,
                clientSecret);
    }

    public HttpResult revoke(String token, String tokenTypeHint) {
        return revokeWithBasic(token, tokenTypeHint, CLIENT_ID, CLIENT_SECRET);
    }

    public HttpResult revokeWithBasic(
            String token, String tokenTypeHint, String clientId, String clientSecret) {
        String body =
                tokenTypeHint == null
                        ? form("token", token)
                        : form(
                                List.of(
                                        new String[] {"token", token},
                                        new String[] {"token_type_hint", tokenTypeHint}));
        return postForm("/oauth2/revoke", body, MediaType.APPLICATION_JSON, clientId, clientSecret);
    }

    public HttpResult logout(Map<String, String> params) {
        UriComponentsBuilder builder =
                UriComponentsBuilder.fromUriString(issuer + "/connect/logout");
        params.forEach(builder::queryParam);
        return get(builder.build().encode().toUri(), MediaType.TEXT_HTML);
    }

    // ------------------------------------------------------------------
    // HTTP primitives
    // ------------------------------------------------------------------

    public HttpResult get(String path, MediaType accept, String bearerToken) {
        var request = client.get().uri(path).accept(accept);
        if (bearerToken != null) {
            request = request.headers(headers -> headers.setBearerAuth(bearerToken));
        }
        var spec = request.exchange().returnResult(String.class);
        return new HttpResult(
                spec.getStatus().value(), spec.getResponseHeaders(), spec.getResponseBody());
    }

    public HttpResult get(String path, MediaType accept) {
        return get(path, accept, null);
    }

    public HttpResult get(URI uri, MediaType accept) {
        var spec = client.get().uri(uri).accept(accept).exchange().returnResult(String.class);
        return new HttpResult(
                spec.getStatus().value(), spec.getResponseHeaders(), spec.getResponseBody());
    }

    public HttpResult postForm(
            String path, String body, MediaType accept, String basicUser, String basicPassword) {
        var request =
                client.post()
                        .uri(path)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .accept(accept);
        if (basicUser != null) {
            request = request.headers(headers -> headers.setBasicAuth(basicUser, basicPassword));
        }
        var spec = request.body(body).exchange().returnResult(String.class);
        return new HttpResult(
                spec.getStatus().value(), spec.getResponseHeaders(), spec.getResponseBody());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    public boolean isLoginRedirect(String location) {
        if (location == null) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(location);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return issuerUri.getHost().equalsIgnoreCase(uri.getHost())
                && issuerUri.getPort() == uri.getPort()
                && "/login".equals(uri.getPath());
    }

    public static String queryParam(String location, String name) {
        if (location == null) {
            return null;
        }
        String rawQuery = URI.create(location).getRawQuery();
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            String key = idx < 0 ? pair : pair.substring(0, idx);
            if (name.equals(URLDecoder.decode(key, StandardCharsets.UTF_8))) {
                String value = idx < 0 ? "" : pair.substring(idx + 1);
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    public static Map<String, String> queryParams(String location) {
        Map<String, String> result = new LinkedHashMap<>();
        if (location == null) {
            return result;
        }
        String rawQuery = URI.create(location).getRawQuery();
        if (rawQuery == null) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            String key =
                    URLDecoder.decode(
                            idx < 0 ? pair : pair.substring(0, idx), StandardCharsets.UTF_8);
            String value =
                    idx < 0
                            ? ""
                            : URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            result.putIfAbsent(key, value);
        }
        return result;
    }

    public static String extract(String html, String name) {
        if (html == null) {
            return null;
        }
        Matcher matcher =
                Pattern.compile("name=\"" + Pattern.quote(name) + "\"[^>]*value=\"([^\"]*)\"")
                        .matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    public static List<String> extractAll(String html, String name) {
        List<String> result = new ArrayList<>();
        if (html == null) {
            return result;
        }
        Matcher matcher =
                Pattern.compile("name=\"" + Pattern.quote(name) + "\"[^>]*value=\"([^\"]*)\"")
                        .matcher(html);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    public static String form(String... keyValues) {
        List<String[]> params = new ArrayList<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.add(new String[] {keyValues[i], keyValues[i + 1]});
        }
        return form(params);
    }

    public static String form(List<String[]> params) {
        StringBuilder builder = new StringBuilder();
        for (String[] param : params) {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(urlEncode(param[0])).append('=').append(urlEncode(param[1]));
        }
        return builder.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record HttpResult(int status, HttpHeaders headers, String body) {

        public String location() {
            return headers.getFirst(HttpHeaders.LOCATION);
        }

        public String header(String name) {
            return headers.getFirst(name);
        }

        public MediaType contentType() {
            return headers.getContentType();
        }

        public boolean isRedirect() {
            return status >= 300 && status < 400;
        }
    }

    public record AuthorizationResult(String code, String state, URI redirectUri) {}

    public record TokenResponse(
            String accessToken,
            String refreshToken,
            String idToken,
            String scope,
            String tokenType,
            long expiresIn,
            Map<String, Object> raw) {

        @SuppressWarnings("unchecked")
        public static TokenResponse parse(String json) {
            Map<String, Object> map = JsonPath.parse(json).json();
            Object expires = map.get("expires_in");
            long expiresIn = expires instanceof Number number ? number.longValue() : -1L;
            return new TokenResponse(
                    (String) map.get("access_token"),
                    (String) map.get("refresh_token"),
                    (String) map.get("id_token"),
                    (String) map.get("scope"),
                    (String) map.get("token_type"),
                    expiresIn,
                    map);
        }
    }
}
