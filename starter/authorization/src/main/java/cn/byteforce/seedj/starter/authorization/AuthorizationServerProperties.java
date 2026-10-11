package cn.byteforce.seedj.starter.authorization;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 授权服务配置属性。
 *
 * <p>前缀：{@code byteforce.security.authorization}</p>
 *
 * <p>范围内只承载 seedj 的组织级缺省：issuer 与注入访问令牌的身份 claim 名称；
 * 客户端注册、令牌定制、端点等协议面配置沿用 Spring Boot 原生属性
 * （{@code spring.security.oauth2.authorizationserver.*}），不重复造轮子。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "byteforce.security.authorization")
public class AuthorizationServerProperties {

    /**
     * 是否启用 seedj 授权服务装配（settings 缺省与 claim 定制），默认 {@code true}。
     */
    private boolean enabled = true;

    /**
     * 授权服务器 issuer 标识（如 {@code https://auth.example.com}）；
     * 设置后注册缺省 {@code AuthorizationServerSettings} Bean（应用可覆盖）。
     */
    private String issuer;

    /**
     * 访问令牌身份 claim 名称（与 starter-resource 的解析配置对齐）。
     */
    private final Claims claims = new Claims();

    /**
     * 返回是否启用授权服务装配。
     *
     * @return 是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置是否启用授权服务装配。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 返回 issuer。
     *
     * @return issuer；未配置时为 {@code null}
     */
    public String getIssuer() {
        return issuer;
    }

    /**
     * 设置 issuer。
     *
     * @param issuer issuer
     */
    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    /**
     * 返回 claim 映射。
     *
     * @return claim 映射
     */
    public Claims getClaims() {
        return claims;
    }

    /**
     * 访问令牌身份 claim 名称映射。
     *
     * @author ByteForce
     * @since 1.0.0
     */
    public static class Claims {

        /**
         * 用户 ID claim 名称，默认 {@code userId}。
         */
        private String userId = "userId";

        /**
         * 租户 ID claim 名称，默认 {@code tenantId}。
         */
        private String tenantId = "tenantId";

        /**
         * 用户名 claim 名称，默认 {@code preferred_username}。
         */
        private String username = "preferred_username";

        /**
         * 自定义权限 claim 名称（字符串数组，取自 {@code CustomUserDetails} 的权限集合；
         * 与 starter-resource 的解析配置对齐）。留空则不注入。
         */
        private String authorities = "authorities";

        /**
         * 返回用户 ID claim 名称。
         *
         * @return claim 名称
         */
        public String getUserId() {
            return userId;
        }

        /**
         * 设置用户 ID claim 名称。
         *
         * @param userId claim 名称
         */
        public void setUserId(String userId) {
            this.userId = userId;
        }

        /**
         * 返回租户 ID claim 名称。
         *
         * @return claim 名称
         */
        public String getTenantId() {
            return tenantId;
        }

        /**
         * 设置租户 ID claim 名称。
         *
         * @param tenantId claim 名称
         */
        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        /**
         * 返回用户名 claim 名称。
         *
         * @return claim 名称
         */
        public String getUsername() {
            return username;
        }

        /**
         * 设置用户名 claim 名称。
         *
         * @param username claim 名称
         */
        public void setUsername(String username) {
            this.username = username;
        }

        /**
         * 返回自定义权限 claim 名称。
         *
         * @return claim 名称
         */
        public String getAuthorities() {
            return authorities;
        }

        /**
         * 设置自定义权限 claim 名称。
         *
         * @param authorities claim 名称
         */
        public void setAuthorities(String authorities) {
            this.authorities = authorities;
        }
    }
}
