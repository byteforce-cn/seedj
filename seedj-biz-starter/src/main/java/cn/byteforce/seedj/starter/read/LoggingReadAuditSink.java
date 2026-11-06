package cn.byteforce.seedj.starter.read;

import cn.byteforce.seedj.read.ReadAccessEvent;
import cn.byteforce.seedj.read.ReadAuditSink;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认读审计：结构化日志 + Micrometer 计数；持久化由业务覆盖 {@link ReadAuditSink}。
 *
 * <p>没有 {@link MeterRegistry} 时只写日志。审计失败由 {@code ReadGateway} 捕获，
 * 不阻断读。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class LoggingReadAuditSink implements ReadAuditSink {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingReadAuditSink.class);

    private final MeterRegistry meters;

    LoggingReadAuditSink(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public void record(ReadAccessEvent event) {
        if (meters != null) {
            Counter.builder("seedj.read.access")
                    .description("read accesses through ReadGateway")
                    .tag("object_type", event.objectType())
                    .tag("operation", event.operation().name())
                    .register(meters)
                    .increment();
        }
        LOG.info("seedj: read object={} operation={} caller={} rows={} scope={} policies={}",
                event.objectType(), event.operation(), event.callerId(),
                event.rowIds().size(), event.scopeDigest(), event.policyIds());
    }
}
