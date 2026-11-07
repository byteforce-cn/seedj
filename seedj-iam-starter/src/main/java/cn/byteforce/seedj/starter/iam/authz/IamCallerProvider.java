package cn.byteforce.seedj.starter.iam.authz;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.CallerProvider;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * IAM 宿主的调用方解析：没有 biz-starter 时也能读取 JWT 主体与权限。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class IamCallerProvider implements CallerProvider {

    private final String tenantId;

    /**
     * 构造调用方解析。
     *
     * @param tenantId 默认租户
     */
    public IamCallerProvider(String tenantId) {
        this.tenantId = tenantId;
    }

    @Override
    public Optional<Caller> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        String username = authentication.getName();
        String userId = username;
        Map<String, Object> attributes = new LinkedHashMap<>();
        Set<String> roles = new LinkedHashSet<>();
        Set<String> permissions = new LinkedHashSet<>();
        Set<String> groups = new LinkedHashSet<>();
        for (GrantedAuthority granted : authentication.getAuthorities()) {
            String value = granted.getAuthority();
            if (value.startsWith("ROLE_")) {
                roles.add(value.substring(5));
            } else if (value.startsWith("PERM_")) {
                permissions.add(value.substring(5));
            } else if (value.startsWith("GROUP_")) {
                groups.add(value.substring(6));
            }
        }
        if (authentication instanceof JwtAuthenticationToken jwt) {
            attributes.putAll(jwt.getToken().getClaims());
            Object claim = jwt.getToken().getClaim("user_id");
            if (claim != null) {
                userId = String.valueOf(claim);
            }
            addAll(roles, jwt.getToken().getClaimAsStringList("roles"));
            addAll(permissions, jwt.getToken().getClaimAsStringList("permissions"));
            addAll(groups, jwt.getToken().getClaimAsStringList("groups"));
        }
        return Optional.of(
                new Caller(
                        userId,
                        username,
                        tenantId,
                        roles,
                        permissions,
                        groups,
                        Set.of(Caller.DEFAULT_AMR),
                        Caller.DEFAULT_ACR,
                        attributes));
    }

    private static void addAll(Set<String> target, List<String> values) {
        if (values != null) {
            target.addAll(values);
        }
    }
}
