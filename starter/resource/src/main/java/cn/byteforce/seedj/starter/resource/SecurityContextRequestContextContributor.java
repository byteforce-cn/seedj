package cn.byteforce.seedj.starter.resource;

import cn.byteforce.seedj.core.context.RequestContext;
import cn.byteforce.seedj.web.core.RequestContextContributor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * 从 Spring Security 安全上下文解析 JWT 身份，写入 {@code RequestContext}。
 *
 * <p>claim 名称由 {@link ResourceServerProperties.Claims} 配置；数字型 claim 解析失败或缺失时
 * 静默置空，不影响其余字段。非 JWT 认证（匿名 / 其他认证方式）直接跳过。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class SecurityContextRequestContextContributor implements RequestContextContributor {

    private final ResourceServerProperties.Claims claims;

    /**
     * 构造贡献器。
     *
     * @param claims claim 名称映射
     */
    public SecurityContextRequestContextContributor(ResourceServerProperties.Claims claims) {
        this.claims = claims;
    }

    @Override
    public void contribute() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            return;
        }
        Jwt jwt = jwtAuthentication.getToken();
        RequestContext.setIdentity(
                parseLong(claimAsString(jwt, claims.getUserId())),
                parseLong(claimAsString(jwt, claims.getTenantId())),
                resolveUsername(jwt));
    }

    private String resolveUsername(Jwt jwt) {
        String username = claimAsString(jwt, claims.getUsername());
        return (username != null && !username.isBlank()) ? username : jwt.getSubject();
    }

    private static String claimAsString(Jwt jwt, String name) {
        Object value = jwt.getClaim(name);
        return (value != null) ? String.valueOf(value) : null;
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
