package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.core.security.AccessTokenRules;
import cn.byteforce.seedj.core.security.Caller;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 本地开发认证：注入固定 {@link Caller}，让 quickstart 不依赖 IdP。
 *
 * <p>只在 {@code seedj.biz.dev-auth.enabled=true} 时装配；生产 profile 由安全门禁拒绝该
 * 组合（见 T2.4）。已有认证不覆盖，便于测试显式模拟无权限调用方。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class LocalDevAuthenticationFilter extends OncePerRequestFilter {

    /** quickstart 的固定调用方 ID；UUID 形状，默认 CallerIdCodec.uuid() 可直接使用。 */
    public static final UUID LOCAL_ADMIN_ID =
            UUID.fromString("018f2e3a-0000-7000-8000-00000000ad11");

    private final Caller caller;

    /**
     * 构造过滤器。
     *
     * @param caller 本地调用方
     */
    public LocalDevAuthenticationFilter(Caller caller) {
        this.caller = caller;
    }

    /**
     * 本地管理员：拥有 HR 读写权限。
     *
     * @return 本地调用方
     */
    public static Caller localAdmin() {
        return new Caller(LOCAL_ADMIN_ID.toString(), "local-admin", "default",
                Set.of("ADMIN"), Set.of("hr.employee.write", "hr.employee.read"), Set.of(),
                Set.of(Caller.DEFAULT_AMR), Caller.DEFAULT_ACR,
                Map.of("org_units", List.of("D01")));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            List<GrantedAuthority> authorities = caller.permissions().stream()
                    .map(permission -> (GrantedAuthority) new SimpleGrantedAuthority(
                            AccessTokenRules.PERMISSION_PREFIX + permission))
                    .toList();
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(caller, null, authorities));
        }
        chain.doFilter(request, response);
    }
}
