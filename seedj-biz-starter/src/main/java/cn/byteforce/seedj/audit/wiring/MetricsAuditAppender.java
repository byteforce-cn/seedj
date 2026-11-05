package cn.byteforce.seedj.audit.wiring;

import cn.byteforce.seedj.audit.AuditAppendRequest;
import cn.byteforce.seedj.audit.AuditAppender;

import java.time.Duration;
import java.util.Objects;

/**
 * 审计指标装饰器：包在事务装饰器外面，量到 append 的总耗时。
 *
 * <p>指标名是 {@code seedj.audit.append.*} 而不是 {@code commit}：事务提交不在本类
 * 范围内，成功也只代表“已追加”，不代表“已提交”。</p>
 */
public final class MetricsAuditAppender implements AuditAppender<String> {

    private final AuditAppender<String> delegate;

    private final AuditMetrics metrics;

    public MetricsAuditAppender(AuditAppender<String> delegate, AuditMetrics metrics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public void append(AuditAppendRequest<String> request) {
        long start = System.nanoTime();
        boolean ok = false;
        try {
            delegate.append(request);
            ok = true;
        } finally {
            metrics.appended(request.getAction(), request.getResourceType(), ok,
                    Duration.ofNanos(System.nanoTime() - start));
        }
    }
}
