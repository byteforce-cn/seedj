package cn.byteforce.seedj.eventbus.wiring;

/**
 * eventbus 消费侧的指标 seam：有 micrometer 时由装配层注入真实实现，否则用 no-op。
 *
 * <p>outbox relay 的指标在 {@link cn.byteforce.seedj.infra.jdbc.outbox.wiring.OutboxMetrics}，
 * 两者不共用接口。
 */
public interface EventBusMetrics {

    /** 消费成功并 ack。 */
    default void consumed() {
    }

    /** 消费失败（retryable=true 表示已安排重试）。 */
    default void consumerFailed(boolean retryable) {
    }

    /** 进入死信。 */
    default void deadLettered() {
    }

    /** 拉取失败。 */
    default void pollFailed() {
    }

    /** 事件已写入 outbox（{@code outbox=true}）或已交给 transport。 */
    default void published(String eventType, boolean outbox) {
    }

    /** 发布失败（序列化、outbox append、transport send）。 */
    default void publishFailed(String eventType) {
    }

    static EventBusMetrics noop() {
        return new EventBusMetrics() {
        };
    }
}
