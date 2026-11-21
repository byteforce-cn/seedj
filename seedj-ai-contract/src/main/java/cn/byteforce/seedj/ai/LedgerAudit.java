package cn.byteforce.seedj.ai;

import java.util.Objects;

/**
 * 账本的体检结果：链自洽之外，还要能发现「绕过账本直插、没有推进链头」的幽灵记录。
 *
 * <p>{@link #verify} 只看链头快照内的记录；{@link #rowCount} 与 {@link #headSequence} 不等，说明表里存在 链头之外的行。两者都为真才
 * {@link #ok()}。
 *
 * @param partition 分区
 * @param rowCount 表内该分区的总行数
 * @param headSequence 链头 {@code last_sequence}
 * @param verification 链头快照内的校对结果
 * @author ByteForce
 * @since 1.0.0
 */
public record LedgerAudit(
        String partition, long rowCount, long headSequence, LedgerVerification verification) {

    /**
     * 校验必填项。
     *
     * @throws NullPointerException 任一参数为 {@code null}
     * @throws IllegalArgumentException 分区为空白或计数为负
     */
    public LedgerAudit {
        Objects.requireNonNull(partition, "partition");
        Objects.requireNonNull(verification, "verification");
        if (partition.isBlank()) {
            throw new IllegalArgumentException("partition must not be blank");
        }
        if (rowCount < 0 || headSequence < 0) {
            throw new IllegalArgumentException("rowCount/headSequence must be >= 0");
        }
    }

    /**
     * 链自洽且没有幽灵记录。
     *
     * @return 两者都为真时返回 {@code true}
     */
    public boolean ok() {
        return verification.ok() && rowCount == headSequence;
    }
}
