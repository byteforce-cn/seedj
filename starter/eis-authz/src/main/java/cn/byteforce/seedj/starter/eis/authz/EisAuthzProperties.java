package cn.byteforce.seedj.starter.eis.authz;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * EIS 授权配置属性。
 *
 * <p>前缀：{@code byteforce.eis.authz}</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "byteforce.eis.authz")
public class EisAuthzProperties {

    /**
     * 是否启用授权自动配置。
     */
    private boolean enabled = true;

    /**
     * 远程授权服务基址（为空 = 不启用远程客户端；权威服务本进程内不需要）。
     */
    private String baseUrl;

    /**
     * 进程内 TTL 缓存时长（fail-open：过期回源）。
     */
    private Duration cacheTtl = Duration.ofSeconds(30);

    /**
     * 远程调用连接超时（有界缺省：授权/令牌端点不可达时快速失败，避免占用消费方请求线程）。
     */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /**
     * 远程调用读取超时（有界缺省：授权/令牌端点挂起时快速失败，避免占用消费方请求线程）。
     */
    private Duration readTimeout = Duration.ofSeconds(10);

    /**
     * 是否启用进程内 TTL 缓存。
     */
    private boolean cacheEnabled = true;

    /**
     * OAuth2 令牌端点（服务间认证；与 client-id/client-secret 同时配置才生效）。
     */
    private String tokenUri;

    /**
     * 服务间认证客户端 ID（client_credentials）。
     */
    private String clientId;

    /**
     * 服务间认证客户端密钥（client_credentials）。
     */
    private String clientSecret;

    /**
     * 令牌作用域（空格分隔；可空）。
     */
    private String scope;

    /**
     * 令牌刷新提前量（避免临界过期）。
     */
    private Duration tokenRefreshSkew = Duration.ofSeconds(30);

    /**
     * 获取是否启用。
     *
     * @return 是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置是否启用。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 获取远程基址。
     *
     * @return 基址；未配置为 {@code null}
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * 设置远程基址。
     *
     * @param baseUrl 基址
     */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * 获取缓存时长。
     *
     * @return TTL
     */
    public Duration getCacheTtl() {
        return cacheTtl;
    }

    /**
     * 设置缓存时长。
     *
     * @param cacheTtl TTL
     */
    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    /**
     * 获取是否启用缓存。
     *
     * @return 是否启用缓存
     */
    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    /**
     * 设置是否启用缓存。
     *
     * @param cacheEnabled 是否启用缓存
     */
    public void setCacheEnabled(boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
    }

    /**
     * 获取连接超时。
     *
     * @return 连接超时
     */
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * 设置连接超时。
     *
     * @param connectTimeout 连接超时
     */
    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    /**
     * 获取读取超时。
     *
     * @return 读取超时
     */
    public Duration getReadTimeout() {
        return readTimeout;
    }

    /**
     * 设置读取超时。
     *
     * @param readTimeout 读取超时
     */
    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    /**
     * 获取令牌端点。
     *
     * @return 令牌端点；未配置为 {@code null}
     */
    public String getTokenUri() {
        return tokenUri;
    }

    /**
     * 设置令牌端点。
     *
     * @param tokenUri 令牌端点（完整 URL）
     */
    public void setTokenUri(String tokenUri) {
        this.tokenUri = tokenUri;
    }

    /**
     * 获取服务间认证客户端 ID。
     *
     * @return 客户端 ID；未配置为 {@code null}
     */
    public String getClientId() {
        return clientId;
    }

    /**
     * 设置服务间认证客户端 ID。
     *
     * @param clientId 客户端 ID
     */
    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    /**
     * 获取服务间认证客户端密钥。
     *
     * @return 客户端密钥；未配置为 {@code null}
     */
    public String getClientSecret() {
        return clientSecret;
    }

    /**
     * 设置服务间认证客户端密钥。
     *
     * @param clientSecret 客户端密钥
     */
    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    /**
     * 获取令牌作用域。
     *
     * @return 作用域（空格分隔）；未配置为 {@code null}
     */
    public String getScope() {
        return scope;
    }

    /**
     * 设置令牌作用域。
     *
     * @param scope 作用域（空格分隔）
     */
    public void setScope(String scope) {
        this.scope = scope;
    }

    /**
     * 获取令牌刷新提前量。
     *
     * @return 提前量
     */
    public Duration getTokenRefreshSkew() {
        return tokenRefreshSkew;
    }

    /**
     * 设置令牌刷新提前量。
     *
     * @param tokenRefreshSkew 提前量
     */
    public void setTokenRefreshSkew(Duration tokenRefreshSkew) {
        this.tokenRefreshSkew = tokenRefreshSkew;
    }
}
