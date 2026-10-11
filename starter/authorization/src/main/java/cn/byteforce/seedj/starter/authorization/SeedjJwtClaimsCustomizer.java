package cn.byteforce.seedj.starter.authorization;

import cn.byteforce.seedj.security.core.CustomUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 访问令牌身份 claim 定制器：当认证主体为 {@link CustomUserDetails} 时，
 * 把用户 / 租户（及用户名、权限串）写入访问令牌，供 starter-resource 侧解析进
 * {@code RequestContext} 与授权判定 —— 两端 claim 名称经配置对齐，形成闭环。
 *
 * <p>仅作用于访问令牌（ACCESS_TOKEN）；其他令牌类型与主体类型不注入任何 claim。
 * 权限 claim（{@code claims.authorities}，默认 {@code authorities}）取自主体的权限集合，
 * 为空或配置留空时不注入。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class SeedjJwtClaimsCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private final AuthorizationServerProperties properties;

    /**
     * 构造定制器。
     *
     * @param properties 授权服务属性
     */
    public SeedjJwtClaimsCustomizer(AuthorizationServerProperties properties) {
        this.properties = properties;
    }

    @Override
    public void customize(JwtEncodingContext context) {
        if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            return;
        }
        claimsFor(context.getPrincipal())
                .forEach((name, value) -> context.getClaims().claim(name, value));
    }

    /**
     * 根据认证主体计算待注入的 claim 集合（纯函数，便于单独验证）。
     *
     * @param authentication 认证主体；可为 {@code null}
     * @return claim 名称 → 值；无可用身份时返回空 Map
     */
    public Map<String, Object> claimsFor(Authentication authentication) {
        Map<String, Object> claims = new LinkedHashMap<>();
        Object principal = (authentication != null) ? authentication.getPrincipal() : null;
        if (!(principal instanceof CustomUserDetails user)) {
            return claims;
        }
        AuthorizationServerProperties.Claims names = properties.getClaims();
        if (user.getUserId() != null) {
            claims.put(names.getUserId(), user.getUserId());
        }
        if (user.getTenantId() != null) {
            claims.put(names.getTenantId(), user.getTenantId());
        }
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            claims.put(names.getUsername(), user.getUsername());
        }
        if (StringUtils.hasText(names.getAuthorities())) {
            List<String> authorities = user.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(authority -> authority != null && !authority.isBlank())
                    .distinct()
                    .toList();
            if (!authorities.isEmpty()) {
                claims.put(names.getAuthorities(), authorities);
            }
        }
        return claims;
    }
}
