package cn.byteforce.seedj.eventbus.spi;

import java.time.Duration;
import java.util.List;

/**
 * 消费侧机制契约：按消费者组领取、确认、重试与死信。
 *
 * <p>只有装配层（consumer container）与队列适配器实现方使用；业务方请实现 {@link
 * cn.byteforce.seedj.eventbus.EventSubscriber}。
 *
 * <h2>语义</h2>
 *
 * <ul>
 *   <li>同一 {@code group} 内，一条消息同一时刻只应被一个实例领取；
 *   <li>不同 group 相互独立，各自都会收到一份；
 *   <li>{@link #ack} 之后该 group 不再收到该消息；
 *   <li>租约到期未 ack 的消息必须可被重新领取；
 *   <li>{@link #deadLetter} 之后该 group 不再收到该消息，但消息进入死信存储；
 *   <li>{@code close()} 幂等且不抛。
 * </ul>
 */
public interface EventConsumerStore extends AutoCloseable {

    /** 领取一批可消费消息。没有消息时返回空列表。 */
    List<ReceivedEvent> poll(PollRequest request);

    /**
     * 确认消费成功。
     *
     * @throws cn.byteforce.seedj.eventbus.EventBusException 租约已失效时（{@code LEASE_LOST}）
     */
    void ack(ReceivedEvent event);

    /** 消费失败但可重试：释放租约，并在 {@code delay} 之后重新可领取。 */
    void retry(ReceivedEvent event, Duration delay, DeliveryFailure failure);

    /** 消费失败且不再重试：进入死信。 */
    void deadLetter(ReceivedEvent event, DeliveryFailure failure);

    /** 查看某个 group 在某个 topic 下的死信（运维/诊断用）。 */
    default List<DeadLetter> deadLetters(String topic, String group, int limit) {
        return List.of();
    }

    @Override
    default void close() {}
}
