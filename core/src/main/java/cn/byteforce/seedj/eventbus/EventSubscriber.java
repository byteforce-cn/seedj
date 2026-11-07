package cn.byteforce.seedj.eventbus;

import cn.byteforce.seedj.domain.event.DomainEvent;

/**
 * 消费者契约：业务方实现本接口并注册为 Spring bean，即可订阅事件。
 *
 * <pre>{@code
 * @Component
 * public final class OrderCancelledSubscriber
 *         implements EventSubscriber<OrderCancelled> {
 *
 *     public EventSubscription subscription() {
 *         return EventSubscription.of("billing", "order.cancelled");
 *     }
 *
 *     public void onEvent(EventEnvelope<OrderCancelled> envelope) {
 *         billingService.onCancelled(envelope.event());
 *     }
 * }
 * }</pre>
 *
 * <p>投递语义：
 *
 * <ul>
 *   <li>同一 group 内多实例竞争，一条消息只会交给一个实例；
 *   <li>方法正常返回 → ack；抛出异常 → 按 {@link cn.byteforce.seedj.core.InfrastructureException#isRetryable()}
 *       重试， 超过最大次数或不可重试 → 死信；
 *   <li>业务方必须用 {@link EventEnvelope#eventId()} 做幂等：at-least-once 意味着可能重复投递。
 * </ul>
 *
 * <p>泛型参数 {@code E} 必须是具体的事件类型（不能是类型变量），装配层用它反序列化 payload。
 */
public interface EventSubscriber<E extends DomainEvent> {

    /** 订阅元数据：group、topic、本实例并发度。 */
    EventSubscription subscription();

    /** 处理一条事件。正常返回即 ack。 */
    void onEvent(EventEnvelope<E> envelope);
}
