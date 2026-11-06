package cn.byteforce.seedj.iam.authz;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 授权契约配置。
 *
 * @param enabled  是否启用（默认 true）
 * @param provider 存储实现：postgres / memory；空则按 IAM store 推断
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "seedj.authz")
public record AuthzProperties(Boolean enabled, String provider) {

    public AuthzProperties {
        enabled = enabled == null || enabled;
    }

    public static AuthzProperties defaults() {
        return new AuthzProperties(true, null);
    }
}
