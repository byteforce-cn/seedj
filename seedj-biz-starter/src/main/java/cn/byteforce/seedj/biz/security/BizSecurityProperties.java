package cn.byteforce.seedj.biz.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 业务服务安全与横截面配置（{@code seedj.biz.*}）。
 *
 * <p>只保留业务方真正需要改的键：issuer / audience / 公开路径 / claim 名。
 * 大小写与默认值在构造期归一化，避免运行期出现 {@code null} 分支。</p>
 *
 * @param enabled     总开关，默认 true
 * @param issuerUri   签发方；留空时回落到 Spring Boot 标准键
 *                    {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}
 * @param audience    期望的 {@code aud}；留空只校验 {@code aud} 非空
 * @param publicPaths 免认证路径，默认健康检查、错误页
 * @param tenantId    多租户标识，默认 {@code default}
 * @param auditDenials 是否为 401/403 写安全审计，默认 true
 * @param cors 允许的 CORS 来源；默认空（不放开），生产必须显式配置
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "seedj.biz")
public record BizSecurityProperties(
        Boolean enabled,
        String issuerUri,
        String audience,
        List<String> publicPaths,
        String tenantId,
        Boolean auditDenials,
        Cors cors) {

    /**
     * 默认公开路径：探针、错误页与 {@code /api/public/**} 约定。
     *
     * <p>{@code /api/public/**} 是本仓库既有的"显式公开"约定，保留它是为了行为
     * 兼容；生产环境应当用 {@code seedj.biz.public-paths} 收窄到实际需要的路径。</p>
     */
    public static final List<String> DEFAULT_PUBLIC_PATHS =
            List.of("/actuator/health", "/actuator/info", "/error", "/api/public/**");

    public BizSecurityProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        issuerUri = issuerUri == null || issuerUri.isBlank() ? null : issuerUri.trim();
        audience = audience == null || audience.isBlank() ? null : audience.trim();
        publicPaths = publicPaths == null || publicPaths.isEmpty()
                ? DEFAULT_PUBLIC_PATHS : List.copyOf(publicPaths);
        tenantId = tenantId == null || tenantId.isBlank() ? "default" : tenantId.trim();
        auditDenials = auditDenials == null ? Boolean.TRUE : auditDenials;
        cors = cors == null ? new Cors(null) : cors;
    }

    /**
     * CORS 配置。
     *
     * @param allowedOrigins 允许的来源；空表示不放开
     */
    public record Cors(List<String> allowedOrigins) {

        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        }
    }

    /** 是否对 401/403 写安全审计。 */
    public boolean auditDenialsEnabled() {
        return Boolean.TRUE.equals(auditDenials);
    }

    /** 允许的 CORS 来源。 */
    public List<String> corsAllowedOrigins() {
        return cors.allowedOrigins();
    }

    /** 是否允许任意来源（仅用于显式本地配置）。 */
    public boolean allowsAnyOrigin() {
        return cors.allowedOrigins().contains("*");
    }

    /** 是否配置了 audience 白名单。 */
    public boolean hasAudience() {
        return audience != null;
    }
}
