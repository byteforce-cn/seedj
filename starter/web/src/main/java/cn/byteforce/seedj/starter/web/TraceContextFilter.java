package cn.byteforce.seedj.starter.web;

import cn.byteforce.seedj.core.context.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 链路追踪过滤器（过滤器链最前沿）：写入 traceId 到 {@code RequestContext} 与 MDC，
 * 按需在响应头回写；请求结束后清理全部上下文（含身份与 MDC），避免线程复用串扰。
 *
 * <p>traceId 解析规则：优先取请求头（默认 {@code X-Trace-Id}，过滤非法字符、限长 64），
 * 缺失或非法时生成 32 位十六进制随机值。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class TraceContextFilter extends OncePerRequestFilter {

    /**
     * traceId 最大长度。
     */
    static final int MAX_TRACE_ID_LENGTH = 64;

    private final String traceIdHeader;

    private final boolean exposeTraceId;

    /**
     * 构造过滤器（构造期提取配置值，不持有可变配置对象）。
     *
     * @param properties Web 运行时属性
     */
    public TraceContextFilter(SeedjWebProperties properties) {
        this.traceIdHeader = properties.getTraceIdHeader();
        this.exposeTraceId = properties.isExposeTraceId();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(traceIdHeader));
        RequestContext.setTraceId(traceId);
        MDC.put(RequestContext.MDC_TRACE_ID, traceId);
        if (exposeTraceId) {
            response.setHeader(traceIdHeader, traceId);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            clearMdc();
            RequestContext.clear();
        }
    }

    private static String resolveTraceId(String incoming) {
        String sanitized = sanitize(incoming);
        if (sanitized != null) {
            return sanitized;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 清洗外部传入的 traceId：去空白、限长、仅保留可打印安全字符；非法时返回 {@code null}。
     *
     * @param value 原始值
     * @return 清洗后的值；无有效内容时返回 {@code null}
     */
    static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.length() > MAX_TRACE_ID_LENGTH) {
            candidate = candidate.substring(0, MAX_TRACE_ID_LENGTH);
        }
        candidate = candidate.replaceAll("[^A-Za-z0-9._-]", "");
        return candidate.isEmpty() ? null : candidate;
    }

    /**
     * 清理请求上下文相关的全部 MDC 键。
     */
    static void clearMdc() {
        MDC.remove(RequestContext.MDC_TRACE_ID);
        MDC.remove(RequestContext.MDC_USER_ID);
        MDC.remove(RequestContext.MDC_TENANT_ID);
        MDC.remove(RequestContext.MDC_USERNAME);
    }
}
