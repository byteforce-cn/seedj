package cn.byteforce.seedj.starter.read;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.core.security.ContextResolver;
import cn.byteforce.seedj.core.security.ScopeContext;
import cn.byteforce.seedj.core.security.ScopeContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 认证之后打开请求事实作用域，{@code finally} 关闭。
 *
 * <p>解析失败 fail closed：返回 503，不带着半份上下文继续。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ScopeContextFilter extends OncePerRequestFilter {

    private final ContextResolver resolver;

    private final CallerProvider callers;

    /**
     * 构造过滤器。
     *
     * @param resolver 事实解析器
     * @param callers 当前调用方
     */
    public ScopeContextFilter(ContextResolver resolver, CallerProvider callers) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.callers = Objects.requireNonNull(callers, "callers");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ScopeContext context;
        try {
            Caller caller = callers.find().orElse(null);
            context = caller == null ? ScopeContext.empty() : resolver.resolve(caller);
        } catch (RuntimeException error) {
            response.setStatus(503);
            response.setContentType("application/json");
            response.getWriter()
                    .write("{\"code\":\"EIS-503-001\",\"message\":\"scope context unavailable\"}");
            return;
        }
        try (ScopeContextHolder.Scope ignored = ScopeContextHolder.open(context)) {
            chain.doFilter(request, response);
        }
    }
}
