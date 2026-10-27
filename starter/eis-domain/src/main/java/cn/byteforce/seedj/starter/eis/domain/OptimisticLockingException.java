package cn.byteforce.seedj.starter.eis.domain;

import cn.byteforce.seedj.core.exception.BaseException;

import java.io.Serial;

/**
 * 聚合版本冲突：同一事件流被并发修改。
 *
 * <p>{@code code} 与 {@code ResponseCode.CONFLICT} 取同值（10008）。
 * 频繁冲突通常说明聚合边界划小了——这是设计信号，不是需要重试掩盖的问题。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class OptimisticLockingException extends BaseException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 错误码，与 {@code ResponseCode.CONFLICT} 同值。
     */
    public static final int CODE = 10008;

    private final long expectedVersion;

    private final long actualVersion;

    /**
     * 构造异常。
     *
     * @param streamId        流标识
     * @param expectedVersion 调用方期望的版本
     * @param actualVersion   存储中的实际版本
     */
    public OptimisticLockingException(String streamId, long expectedVersion, long actualVersion) {
        super(CODE, """
                Optimistic locking conflict on event stream "%s".
                  expected version: %d
                  actual version:   %d
                Fix: reload the aggregate and reapply the command; if this happens often,
                     the aggregate boundary is probably too small.
                """.formatted(streamId, expectedVersion, actualVersion));
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    /**
     * 返回调用方期望的版本。
     *
     * @return 期望版本
     */
    public long getExpectedVersion() {
        return expectedVersion;
    }

    /**
     * 返回存储中的实际版本。
     *
     * @return 实际版本
     */
    public long getActualVersion() {
        return actualVersion;
    }
}
