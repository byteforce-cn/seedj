package cn.byteforce.seedj.outbox.wiring;

import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;

/**
 * micrometer 实现的 outbox 指标。
 */
final class MicrometerOutboxMetrics implements OutboxMetrics {

    private final MeterRegistry registry;

    MicrometerOutboxMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void claimed(int count) {
        registry.counter("seedj.outbox.claimed").increment(count);
    }

    @Override
    public void sent() {
        registry.counter("seedj.outbox.sent").increment();
    }

    @Override
    public void failed(boolean retryable) {
        registry.counter("seedj.outbox.failed", "retryable", Boolean.toString(retryable))
                .increment();
    }

    @Override
    public void leaseLost() {
        registry.counter("seedj.outbox.lease.lost").increment();
    }

    @Override
    public void claimFailed() {
        registry.counter("seedj.outbox.claim.failed").increment();
    }

    @Override
    public void oldestLag(Duration lag) {
        registry.timer("seedj.outbox.lag").record(lag);
    }
}
