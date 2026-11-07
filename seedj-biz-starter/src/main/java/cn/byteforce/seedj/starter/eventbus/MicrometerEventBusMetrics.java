package cn.byteforce.seedj.starter.eventbus;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** micrometer 实现的 eventbus 指标（发布 + 消费）。 */
final class MicrometerEventBusMetrics implements EventBusMetrics {

    private final MeterRegistry registry;

    private final ConcurrentMap<String, Counter> published = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, Counter> publishFailed = new ConcurrentHashMap<>();

    MicrometerEventBusMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void consumed() {
        registry.counter("seedj.eventbus.consumer.consumed").increment();
    }

    @Override
    public void consumerFailed(boolean retryable) {
        registry.counter("seedj.eventbus.consumer.failed", "retryable", Boolean.toString(retryable))
                .increment();
    }

    @Override
    public void deadLettered() {
        registry.counter("seedj.eventbus.consumer.dead.lettered").increment();
    }

    @Override
    public void pollFailed() {
        registry.counter("seedj.eventbus.consumer.poll.failed").increment();
    }

    @Override
    public void published(String eventType, boolean outbox) {
        String mode = outbox ? "outbox" : "transport";
        published
                .computeIfAbsent(
                        eventType + '|' + mode,
                        key ->
                                registry.counter(
                                        "seedj.eventbus.publish.count",
                                        "eventType",
                                        eventType,
                                        "mode",
                                        mode))
                .increment();
    }

    @Override
    public void publishFailed(String eventType) {
        publishFailed
                .computeIfAbsent(
                        eventType,
                        key ->
                                registry.counter(
                                        "seedj.eventbus.publish.failed", "eventType", eventType))
                .increment();
    }
}
