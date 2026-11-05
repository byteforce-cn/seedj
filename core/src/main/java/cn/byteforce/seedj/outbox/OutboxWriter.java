package cn.byteforce.seedj.outbox;

/**
 * Outbox 的业务入口：把一条记录写入事务性 outbox。
 *
 * <p>业务代码只做一件事：
 * <pre>{@code
 * @Transactional
 * public void cancel(OrderId id) {
 *     repository.save(...);
 *     outboxWriter.append(OutboxRecord.of("order.cancelled", id.value(), "order.cancelled", payload));
 * }
 * }</pre>
 *
 * <p>{@link #append(OutboxRecord)} 必须在<b>活动事务内</b>调用，与业务写入同生共死；
 * 投递由 outbox relay 异步完成（at-least-once）。业务方不接触 claim / lease / attempts。
 *
 * <p>没有可用 {@link cn.byteforce.seedj.outbox.spi.OutboxStore} 时抛
 * {@link OutboxException}（{@code STORE_UNAVAILABLE}），不静默丢弃。
 */
public interface OutboxWriter {

    /**
     * 写入一条待投递记录。按 {@link OutboxRecord#messageId()} 幂等。
     */
    void append(OutboxRecord record);
}
