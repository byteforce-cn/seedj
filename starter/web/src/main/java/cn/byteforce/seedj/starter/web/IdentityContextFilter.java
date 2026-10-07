package cn.byteforce.seedj.starter.web;

import cn.byteforce.seedj.core.context.RequestContext;
import cn.byteforce.seedj.web.core.RequestContextContributor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 身份上下文过滤器：在安全链（order=-100）之后执行，调度所有
 * {@link RequestContextContributor}（如 JWT 身份解析）把用户 / 租户 / 用户名写入
 * {@code RequestContext}，并同步到 MDC；请求结束后清理身份字段。
 *
 * <p>贡献器抛出的运行时异常被隔离（不影响请求处理），仅记录告警日志。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class IdentityContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdentityContextFilter.class);

    private final List<RequestContextContributor> contributors;

    /**
     * 构造过滤器。
     *
     * @param contributors 身份贡献器列表（可为空）
     */
    public IdentityContextFilter(List<RequestContextContributor> contributors) {
        this.contributors = List.copyOf(contributors);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        for (RequestContextContributor contributor : contributors) {
            try {
                contributor.contribute();
            } catch (RuntimeException ex) {
                // 贡献器失败不应破坏请求处理；记录后继续
                log.warn("身份上下文贡献器执行失败: {}", contributor.getClass().getName(), ex);
            }
        }
        RequestContext.userId().ifPresent(v -> MDC.put(RequestContext.MDC_USER_ID, String.valueOf(v)));
        RequestContext.tenantId().ifPresent(v -> MDC.put(RequestContext.MDC_TENANT_ID, String.valueOf(v)));
        RequestContext.username().ifPresent(v -> MDC.put(RequestContext.MDC_USERNAME, v));
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(RequestContext.MDC_USER_ID);
            MDC.remove(RequestContext.MDC_TENANT_ID);
            MDC.remove(RequestContext.MDC_USERNAME);
            RequestContext.setIdentity(null, null, null);
        }
    }
}
