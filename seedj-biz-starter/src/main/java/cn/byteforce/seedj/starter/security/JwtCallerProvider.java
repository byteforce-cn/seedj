package cn.byteforce.seedj.starter.security;

import cn.byteforce.seedj.core.security.AccessTokenRules;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.CallerProvider;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * {@link CallerProvider} 的默认实现：先看异步作用域，再读 Spring Security 上下文。
 *
 * <p>只读令牌声明，<b>不查库</b>：这是业务服务能够零 IAM 依赖的全部前提。
 * 需要资源级 ABAC 的服务显式接入 IAM 的策略决策点，而不是让每个资源服务器
 * 都携带用户存储。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class JwtCallerProvider implements CallerProvider {

    private final CallerPropagation propagation;
    private final JwtClaimsMapper mapper;
    private final String tenantId;

    public JwtCallerProvider(CallerPropagation propagation, JwtClaimsMapper mapper,
                             String tenantId) {
        this.propagation = propagation;
        this.mapper = mapper;
        this.tenantId = tenantId;
    }

    @Override
    public Optional<Caller> find() {
        Optional<Caller> propagated = propagation.current();
        if (propagated.isPresent()) {
            return propagated;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // 刻意不检查 isAuthenticated()：Spring Security 7 里在过滤器之外手工构造的
        // JwtAuthenticationToken 会报 false，用它做判据会把合法请求判成匿名。
        if (authentication == null
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        if (authentication.getPrincipal() instanceof Caller caller) {
            return Optional.of(caller);
        }
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            return Optional.of(AccessTokenRules.toCaller(mapper.map(jwt), tenantId));
        }
        return Optional.of(fromAuthorities(authentication));
    }

    /**
     * 非 JWT 认证（表单登录、测试用 mock user）的兜底：按前缀还原角色/权限/组。
     */
    private Caller fromAuthorities(Authentication authentication) {
        Set<String> roles = new LinkedHashSet<>();
        Set<String> permissions = new LinkedHashSet<>();
        Set<String> groups = new LinkedHashSet<>();
        for (GrantedAuthority granted : authentication.getAuthorities()) {
            String value = granted.getAuthority();
            if (value.startsWith(AccessTokenRules.ROLE_PREFIX)) {
                roles.add(value.substring(AccessTokenRules.ROLE_PREFIX.length()));
            } else if (value.startsWith(AccessTokenRules.PERMISSION_PREFIX)) {
                permissions.add(value.substring(AccessTokenRules.PERMISSION_PREFIX.length()));
            } else if (value.startsWith(AccessTokenRules.GROUP_PREFIX)) {
                groups.add(value.substring(AccessTokenRules.GROUP_PREFIX.length()));
            }
        }
        String name = authentication.getName();
        return new Caller(name, name, tenantId, roles, permissions, groups,
                Set.of(Caller.DEFAULT_AMR), Caller.DEFAULT_ACR, java.util.Map.of());
    }
}
