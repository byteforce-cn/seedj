package cn.byteforce.seedj.starter.security;

import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.core.security.SecurityAuditEvent;
import cn.byteforce.seedj.core.security.SecurityAuditSink;
import cn.byteforce.seedj.core.security.SecurityErrorCode;
import cn.byteforce.seedj.id.IdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 401/403 的安全审计：在执行点（过滤器 / 异常处理）记录，业务代码不参与。
 *
 * <p>审计失败绝不能改变响应结果：所有异常在这里吞掉并落 WARN。缺少
 * {@code SecurityAuditSink} / {@code IdGenerator} 时静默跳过（与
 * {@code AnnotationCommandAudit} 的降级策略一致）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class SecurityDenialAudit {

    private static final Logger log = LoggerFactory.getLogger(SecurityDenialAudit.class);

    private final ObjectProvider<SecurityAuditSink> sinks;
    private final ObjectProvider<IdGenerator<String>> idGenerators;
    private final CallerProvider callers;
    private final BizSecurityProperties properties;

    public SecurityDenialAudit(ObjectProvider<SecurityAuditSink> sinks,
                               ObjectProvider<IdGenerator<String>> idGenerators,
                               CallerProvider callers,
                               BizSecurityProperties properties) {
        this.sinks = sinks;
        this.idGenerators = idGenerators;
        this.callers = callers;
        this.properties = properties;
    }

    /** 记录一次被拒绝的请求；任何异常都不向调用方传播。 */
    public void recordDenial(HttpServletRequest request, SecurityErrorCode code) {
        if (!properties.auditDenialsEnabled()) {
            return;
        }
        try {
            SecurityAuditSink sink = sinks.getIfAvailable();
            IdGenerator<String> ids = idGenerators.getIfAvailable();
            if (sink == null || ids == null) {
                return;
            }
            String method = request.getMethod() == null ? "unknown" : request.getMethod();
            sink.record(SecurityAuditEvent.of(ids.id(), callers.find().orElse(null),
                    "http." + method.toLowerCase(java.util.Locale.ROOT), "http",
                    request.getRequestURI(), SecurityAuditEvent.OUTCOME_DENIED, code.name()));
        } catch (RuntimeException e) {
            log.warn("seedj security audit failed for {} {}", request.getMethod(),
                    request.getRequestURI(), e);
        }
    }
}
