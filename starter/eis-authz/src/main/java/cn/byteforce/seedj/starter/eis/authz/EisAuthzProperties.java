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
     * 是否启用进程内 TTL 缓存。
     */
    private boolean cacheEnabled = true;

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
}
