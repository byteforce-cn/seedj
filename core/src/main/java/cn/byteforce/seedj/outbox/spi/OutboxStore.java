package cn.byteforce.seedj.outbox.spi;

import cn.byteforce.seedj.outbox.OutboxRecord;
import java.time.Duration;
import java.util.List;

/**
 * Outbox 存储的<b>机制</b>契约。只有 relay 和 provider 实现方使用。
 *
 * <p>业务方请注入 {@link cn.byteforce.seedj.outbox.OutboxWriter}，不要直接注入本接口。
 *
 * <h2>语义</h2>
 *
 * <ul>
 *   <li>{@link #append} 必须在业务事务内调用，与业务写入同生共死；{@code messageId} 幂等。
 *   <li>{@link #claim} 返回的记录按 {@code partitionKey} 内的先后顺序排他领取， 同一分区内后一条不得先于前一条被领取。
 *   <li>{@link #markSent} / {@link #markFailed} 必须校验 {@link ClaimToken} 的租约； 租约已失效时抛 {@code
 *       OutboxException(LEASE_LOST)}，不得静默写入。
 *   <li>{@link #retry} / {@link #discard} 是运维面操作，只对 FAILED 记录生效。
 *   <li>{@link #close} 幂等且不抛。
 * </ul>
 */
public interface OutboxStore extends AutoCloseable {

    /** 写入一条待投递记录；由存储分配分区内序号。 */
    void append(OutboxRecord record);

    /** 领取一批可投递记录（含租约到期回收）。 */
    List<ClaimedRecord> claim(ClaimRequest request);

    /** 标记投递成功。 */
    void markSent(ClaimToken token);

    /** 标记投递失败；{@code retryable=false} 时进入死信（FAILED），等待人工 retry/discard。 */
    void markFailed(ClaimToken token, OutboxFailure failure, Duration retryDelay);

    /** 是否支持续租（长投递场景）。 */
    default boolean supportsRenewal() {
        return false;
    }

    /** 续租；{@code false} 表示租约已失效。 */
    default boolean renewLease(ClaimToken token) {
        return false;
    }

    /** 人工重投一条死信。 */
    void retry(String recordId, String operator);

    /** 人工丢弃一条死信（标记为已处理，不再投递）。 */
    void discard(String recordId, String operator);

    @Override
    default void close() {}
}
