package cn.byteforce.seedj.eventbus;

import cn.byteforce.seedj.domain.event.DomainEvent;

import java.util.UUID;

/**
 * 事件发布的业务入口。
 *
 * <p>业务代码只做一件事：
 * <pre>{@code
 * @Transactional
 * public void cancel(OrderId id) {
 *     Order order = repository.loadOrThrow(id);
 *     order.cancel();
 *     eventPublisher.publish(new OrderCancelled(id.value()));
 * }
 * }</pre>
 *
 * <h2>投递保证</h2>
 * <ul>
 *   <li>存在 outbox 能力时：写入 outbox（与业务数据同事务），由 outbox relay 异步投递
 *       —— at-least-once。</li>
 *   <li>没有 outbox 时：直接写 transport —— at-most-once，仅供本地开发/测试。</li>
 * </ul>
 *
 * <p>outbox 激活时，{@link #publish(DomainEvent)} 必须在<b>活动事务内</b>调用；
 * 违反时抛 {@link cn.byteforce.seedj.outbox.OutboxException}（{@code TX_REQUIRED}）。
 * 需要能力无关处理时，业务方可以 catch {@link cn.byteforce.seedj.core.InfrastructureException}。
 */
public interface EventPublisher {

    /**
     * 发布一个领域事件。topic 取 {@code @EventType} 的逻辑名，schema 版本取注解版本。
     *
     * <p>事件对象本身不会被修改；payload 由已注册的
     * {@link cn.byteforce.seedj.eventbus.spi.EventPayloadSerializer} 序列化。
     */
    void publish(DomainEvent event);

    /**
     * 使用调用方分配的稳定事件 ID 发布。
     *
     * <p>事件溯源命令需要在写入 outbox 之前就知道事件 ID，才能把同一批 ID 放进
     * 命令回执与命令记录。默认实现忽略 ID 并委托 {@link #publish(DomainEvent)}；
     * 能把 ID 带进 outbox/消息的适配器（如 {@code DefaultEventPublisher}）应覆盖本方法。</p>
     *
     * @param event   领域事件
     * @param eventId 调用方分配的事件 ID（非空）
     */
    default void publish(DomainEvent event, UUID eventId) {
        publish(event);
    }

    /**
     * 发布一条已构造好的消息（逃生舱：迁移历史事件、跨语言消息等）。
     */
    void publish(EventMessage message);
}
