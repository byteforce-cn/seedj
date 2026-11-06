package cn.byteforce.seedj.starter.audit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * micrometer 实现的审计指标。
 */
final class MicrometerAuditMetrics implements AuditMetrics {

    private final MeterRegistry registry;

    private final ConcurrentMap<String, Timer> timers = new ConcurrentHashMap<>();

    MicrometerAuditMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void appended(String action, String resourceType, boolean ok, Duration took) {
        timers.computeIfAbsent(action + '|' + resourceType + '|' + ok,
                key -> Timer.builder("seedj.audit.append.time")
                        .tag("action", action)
                        .tag("resourceType", resourceType)
                        .tag("status", ok ? "success" : "failed")
                        .register(registry)).record(took);
    }
}
