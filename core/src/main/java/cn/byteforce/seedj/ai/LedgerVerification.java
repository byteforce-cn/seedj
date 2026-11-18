package cn.byteforce.seedj.ai;

import java.util.Objects;
import java.util.UUID;

/**
 * 账本链校对结果：整条链是否连续、可复算。
 *
 * <p>失败时给出第一条对不上的记录与期望/实际哈希，便于定位篡改或漏写；{@code checkedRecords} 是校对覆盖的记录数，
 * 用它区分“链为空”和“链错了”。
 *
 * @param ok 是否通过校对
 * @param partition 被校对的分区
 * @param checkedRecords 已校对记录数
 * @param failedRecordId 第一条对不上的记录；通过时为 {@code null}
 * @param expectedHash 期望的哈希；通过时为 {@code null}
 * @param actualHash 实际的哈希；通过时为 {@code null}
 * @author ByteForce
 * @since 1.0.0
 */
public record LedgerVerification(
        boolean ok,
        String partition,
        long checkedRecords,
        UUID failedRecordId,
        String expectedHash,
        String actualHash) {

    /**
     * 校验分区名。
     *
     * @throws NullPointerException {@code partition} 为 {@code null}
     * @throws IllegalArgumentException {@code partition} 为空白
     */
    public LedgerVerification {
        Objects.requireNonNull(partition, "partition");
        if (partition.isBlank()) {
            throw new IllegalArgumentException("partition must not be blank");
        }
        if (checkedRecords < 0) {
            throw new IllegalArgumentException("checkedRecords must be >= 0");
        }
    }

    /**
     * 校对通过。
     *
     * @param partition 被校对的分区
     * @param checkedRecords 已校对记录数
     * @return 通过的结果
     */
    public static LedgerVerification ok(String partition, long checkedRecords) {
        return new LedgerVerification(true, partition, checkedRecords, null, null, null);
    }

    /**
     * 校对失败：链在某条记录上对不上。
     *
     * @param partition 被校对的分区
     * @param checkedRecords 已校对记录数
     * @param failedRecordId 第一条对不上的记录
     * @param expectedHash 期望的哈希
     * @param actualHash 实际的哈希
     * @return 失败的结果
     */
    public static LedgerVerification mismatch(
            String partition,
            long checkedRecords,
            UUID failedRecordId,
            String expectedHash,
            String actualHash) {
        return new LedgerVerification(
                false, partition, checkedRecords, failedRecordId, expectedHash, actualHash);
    }
}
