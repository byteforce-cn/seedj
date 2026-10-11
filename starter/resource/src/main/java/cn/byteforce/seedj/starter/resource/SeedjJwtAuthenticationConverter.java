package cn.byteforce.seedj.starter.resource;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * seedj 资源服务 JWT 认证转换器：在 Spring Security 缺省映射（{@code scope/scp} →
 * {@code SCOPE_*} 前缀权限）基础上，额外消费配置的自定义权限 claim
 * （{@link ResourceServerProperties.Claims#getAuthorities()}，默认 {@code authorities}，
 * 字符串数组），与授权侧 {@code SeedjJwtClaimsCustomizer} 的签发口径闭环。
 *
 * <p>claim 缺失、非集合或配置留空时保持缺省映射行为不变；权限串去重后与 scope 权限合并。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class SeedjJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();

    private final String authoritiesClaim;

    /**
     * 构造转换器。
     *
     * @param claims claim 名称映射（自定义权限 claim；留空则关闭扩展映射）
     */
    public SeedjJwtAuthenticationConverter(ResourceServerProperties.Claims claims) {
        this.authoritiesClaim = claims.getAuthorities();
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        AbstractAuthenticationToken token = delegate.convert(jwt);
        if (!(token instanceof JwtAuthenticationToken jwtToken) || !StringUtils.hasText(authoritiesClaim)) {
            return token;
        }
        Object raw = jwt.getClaim(authoritiesClaim);
        if (!(raw instanceof Collection<?> values) || values.isEmpty()) {
            return token;
        }
        Collection<GrantedAuthority> authorities = new LinkedHashSet<>(token.getAuthorities());
        for (Object value : values) {
            String authority = (value != null) ? String.valueOf(value).trim() : "";
            if (!authority.isEmpty()) {
                authorities.add(new SimpleGrantedAuthority(authority));
            }
        }
        return new JwtAuthenticationToken(jwt, List.copyOf(authorities), jwtToken.getName());
    }
}
