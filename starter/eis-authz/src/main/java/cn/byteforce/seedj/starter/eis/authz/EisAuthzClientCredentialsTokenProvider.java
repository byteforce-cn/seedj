package cn.byteforce.seedj.starter.eis.authz;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * OAuth2 {@code client_credentials} 令牌提供者：按需向令牌端点换取访问令牌，
 * 缓存至「过期时刻 - 刷新提前量」，过期（或临近过期）自动刷新。
 *
 * <p>语义要点：</p>
 * <ul>
 *   <li>请求形态：{@code POST <token-uri>}，{@code application/x-www-form-urlencoded}，
 *       {@code grant_type=client_credentials}（+ 可选 {@code scope}），
 *       客户端认证采用 HTTP Basic（{@code client_secret_basic}）；</li>
 *   <li>缓存与刷新：令牌按 {@code expires_in}（缺省 300 秒）缓存，
 *       提前 {@code token-refresh-skew}（缺省 30 秒）刷新；</li>
 *   <li>失败口径：令牌端点非 2xx / 响应缺少 {@code access_token} 一律抛
 *       {@link EisAuthzRemoteException}（fail-closed——不得静默降级为无认证调用）；</li>
 *   <li>配置不完整（缺 {@code token-uri}/{@code client-id}/{@code client-secret}）
 *       在构造期 fail-fast，避免应用静默退化为 401。</li>
 * </ul>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisAuthzClientCredentialsTokenProvider implements EisAuthzTokenProvider {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final long DEFAULT_EXPIRES_IN_SECONDS = 300L;

    private final RestClient tokenClient;

    private final String tokenUri;

    private final String clientId;

    private final String clientSecret;

    private final String scope;

    private final Duration refreshSkew;

    private final Clock clock;

    private volatile CachedToken cached;

    /**
     * 构造令牌提供者（系统时钟）。
     *
     * @param tokenClient 令牌端点客户端（无基址要求；{@code tokenUri} 为完整地址）
     * @param tokenUri    令牌端点（完整 URL）
     * @param clientId    客户端 ID
     * @param clientSecret 客户端密钥
     * @param scope       作用域（空格分隔；可空）
     * @param refreshSkew 刷新提前量（可空 = 不提前）
     */
    public EisAuthzClientCredentialsTokenProvider(RestClient tokenClient, String tokenUri, String clientId,
                                                  String clientSecret, String scope, Duration refreshSkew) {
        this(tokenClient, tokenUri, clientId, clientSecret, scope, refreshSkew, Clock.systemUTC());
    }

    /**
     * 构造令牌提供者（可注入时钟，测试/定制用）。
     *
     * @param tokenClient  令牌端点客户端（无基址要求；{@code tokenUri} 为完整地址）
     * @param tokenUri     令牌端点（完整 URL）
     * @param clientId     客户端 ID
     * @param clientSecret 客户端密钥
     * @param scope        作用域（空格分隔；可空）
     * @param refreshSkew  刷新提前量（可空 = 不提前）
     * @param clock        时钟
     */
    public EisAuthzClientCredentialsTokenProvider(RestClient tokenClient, String tokenUri, String clientId,
                                                  String clientSecret, String scope, Duration refreshSkew,
                                                  Clock clock) {
        if (tokenUri == null || tokenUri.isBlank()) {
            throw new IllegalStateException("byteforce.eis.authz.token-uri 未配置：服务间认证需要令牌端点");
        }
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("byteforce.eis.authz.client-id 未配置：服务间认证需要客户端 ID");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException(
                    "byteforce.eis.authz.client-secret 未配置：服务间认证需要客户端密钥");
        }
        this.tokenClient = tokenClient;
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scope = scope;
        this.refreshSkew = refreshSkew == null ? Duration.ZERO : refreshSkew;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public Optional<String> accessToken() {
        CachedToken current = cached;
        Instant now = clock.instant();
        if (current != null && now.isBefore(current.refreshAt)) {
            return Optional.of(current.value);
        }
        synchronized (this) {
            current = cached;
            if (current != null && now.isBefore(current.refreshAt)) {
                return Optional.of(current.value);
            }
            cached = fetch();
            return Optional.of(cached.value);
        }
    }

    private CachedToken fetch() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        if (scope != null && !scope.isBlank()) {
            form.add("scope", scope);
        }
        String payload = tokenClient.post().uri(tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .headers(headers -> headers.setBasicAuth(clientId, clientSecret, StandardCharsets.UTF_8))
                .body(form)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    String text = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    throw new EisAuthzRemoteException(response.getStatusCode().value(), tokenUri, text);
                })
                .body(String.class);
        JsonNode node = payload == null ? null : MAPPER.readTree(payload);
        if (node == null || node.get("access_token") == null || node.get("access_token").stringValue().isBlank()) {
            throw new EisAuthzRemoteException(502, tokenUri, "令牌响应缺少 access_token");
        }
        Instant now = clock.instant();
        Instant refreshAt = now.plusSeconds(expiresInSeconds(node)).minus(refreshSkew);
        if (refreshAt.isBefore(now)) {
            refreshAt = now;
        }
        return new CachedToken(node.get("access_token").stringValue(), refreshAt);
    }

    private static long expiresInSeconds(JsonNode node) {
        JsonNode expiresIn = node.get("expires_in");
        if (expiresIn == null || expiresIn.isNull()) {
            return DEFAULT_EXPIRES_IN_SECONDS;
        }
        if (expiresIn.isNumber() && expiresIn.asLong() > 0) {
            return expiresIn.asLong();
        }
        if (expiresIn.isString()) {
            try {
                long parsed = Long.parseLong(expiresIn.stringValue().trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // 非法值回落缺省
            }
        }
        return DEFAULT_EXPIRES_IN_SECONDS;
    }

    /**
     * 缓存令牌（值 + 刷新时刻）。
     */
    private static final class CachedToken {

        private final String value;

        private final Instant refreshAt;

        private CachedToken(String value, Instant refreshAt) {
            this.value = value;
            this.refreshAt = refreshAt;
        }
    }
}
