package cn.byteforce.seedj.starter.eventbus;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** micrometer 指标名与标签：看板与告警按这些名字采集，改名即破坏契约。 */
class MicrometerEventBusMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerEventBusMetrics metrics = new MicrometerEventBusMetrics(registry);

    @Test
    @DisplayName("消费成功/死信/轮询失败使用固定指标名并累加")
    void consumerCounters() {
        metrics.consumed();
        metrics.consumed();
        metrics.deadLettered();
        metrics.pollFailed();

        assertThat(count("seedj.eventbus.consumer.consumed")).isEqualTo(2.0);
        assertThat(count("seedj.eventbus.consumer.dead.lettered")).isEqualTo(1.0);
        assertThat(count("seedj.eventbus.consumer.poll.failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("消费失败按 retryable 打标签，两个维度各自计数")
    void consumerFailureTaggedByRetryable() {
        metrics.consumerFailed(true);
        metrics.consumerFailed(true);
        metrics.consumerFailed(false);

        assertThat(count("seedj.eventbus.consumer.failed", "retryable", "true")).isEqualTo(2.0);
        assertThat(count("seedj.eventbus.consumer.failed", "retryable", "false")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("发布成功按 eventType + mode 打标签，缓存同一个 Counter")
    void publishedTaggedByEventTypeAndMode() {
        metrics.published("OrderPaid", true);
        metrics.published("OrderPaid", true);
        metrics.published("OrderPaid", false);
        metrics.published("OrderShipped", false);

        assertThat(
                        count(
                                "seedj.eventbus.publish.count",
                                "eventType",
                                "OrderPaid",
                                "mode",
                                "outbox"))
                .isEqualTo(2.0);
        assertThat(
                        count(
                                "seedj.eventbus.publish.count",
                                "eventType",
                                "OrderPaid",
                                "mode",
                                "transport"))
                .isEqualTo(1.0);
        assertThat(
                        count(
                                "seedj.eventbus.publish.count",
                                "eventType",
                                "OrderShipped",
                                "mode",
                                "transport"))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("发布失败按 eventType 打标签")
    void publishFailedTaggedByEventType() {
        metrics.publishFailed("OrderPaid");
        metrics.publishFailed("OrderPaid");

        assertThat(count("seedj.eventbus.publish.failed", "eventType", "OrderPaid")).isEqualTo(2.0);
    }

    private double count(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
