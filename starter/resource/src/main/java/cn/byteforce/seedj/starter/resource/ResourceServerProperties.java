package cn.byteforce.seedj.starter.resource;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * JWT 资源服务配置属性。
 *
 * <p>前缀：{@code byteforce.security.resource}</p>
 *
 * <p>JWT 解码器（{@code JwtDecoder}）不在本组件内定义：由应用通过 Spring Boot 原生属性
 * （{@code spring.security.oauth2.resourceserver.jwt.*}）配置，或自行提供 {@code JwtDecoder} Bean。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "byteforce.security.resource")
public class ResourceServerProperties {

    /**
     * 是否启用资源服务自动配置，默认 {@code true}。
     */
    private boolean enabled = true;

    /**
     * 免认证路径（Spring MVC Ant 模式），默认放行错误页与健康检查。
     */
    private List<String> permitPaths = new ArrayList<>(List.of(
            "/error",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info"));

    /**
     * JWT claim 映射。
     */
    private final Claims claims = new Claims();

    /**
     * 返回是否启用资源服务自动配置。
     *
     * @return 是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置是否启用资源服务自动配置。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 返回免认证路径列表。
     *
     * @return 免认证路径
     */
    public List<String> getPermitPaths() {
        return permitPaths;
    }

    /**
     * 设置免认证路径列表。
     *
     * @param permitPaths 免认证路径
     */
    public void setPermitPaths(List<String> permitPaths) {
        this.permitPaths = permitPaths;
    }

    /**
     * 返回 JWT claim 映射。
     *
     * @return claim 映射
     */
    public Claims getClaims() {
        return claims;
    }

    /**
     * JWT claim 名称映射：身份信息（用户 / 租户 / 用户名）与自定义权限 claim。
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
         * 用户名 claim 名称（缺失时回落到 {@code sub}），默认 {@code preferred_username}。
         */
        private String username = "preferred_username";

        /**
         * 自定义权限 claim 名称（字符串数组，与标准 scope/scp 权限合并），默认 {@code authorities}。
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
