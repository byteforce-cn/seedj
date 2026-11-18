package cn.byteforce.seedj.ai;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 一条已落账的决策记录：哈希链上的一节。
 *
 * <p>记录只追加：{@code sequence} 在分区内从 1 递增，{@code prevHash} 是同一分区上一条的 {@code recordHash}，
 * {@code recordHash} 覆盖 {@code body} 与链上前缀。校验一条记录能证明“这条没被改过”，校验整条链能证明“没有记录被删掉”。
 *
 * <p>分区粒度是租户（T0 无域段）：链按租户独立，租户之间不共享序列，也不互相暴露记录数。
 *
 * @param recordId 记录标识
 * @param decisionId 决策标识
 * @param kind 记录类型
 * @param partition 分区名
 * @param sequence 分区内序号，从 1 开始
 * @param prevHash 前一条记录的哈希（64 位小写十六进制）；分区首条为全零
 * @param recordHash 本条记录的哈希（64 位小写十六进制）
 * @param recordedAt 落账时间
 * @param tenantId 租户
 * @param body 记录体（按 {@code kind} 不同而不同）；允许 null value，键序保留
 * @author ByteForce
 * @since 1.0.0
 */
public record DecisionRecord(
        UUID recordId,
        UUID decisionId,
        DecisionRecordKind kind,
        String partition,
        long sequence,
        String prevHash,
        String recordHash,
        Instant recordedAt,
        UUID tenantId,
        Map<String, Object> body) {

    /** 哈希的线格式：64 位小写十六进制。 */
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    /**
     * 校验必填项、序号下界与哈希格式。
     *
     * @throws NullPointerException 任一组件为 {@code null}
     * @throws IllegalArgumentException 分区/哈希为空白、哈希格式不合法、{@code sequence < 1} 或记录体为空
     */
    public DecisionRecord {
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(partition, "partition");
        if (partition.isBlank()) {
            throw new IllegalArgumentException("partition must not be blank");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be >= 1");
        }
        prevHash = requiredHash(prevHash, "prevHash");
        recordHash = requiredHash(recordHash, "recordHash");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(tenantId, "tenantId");
        body = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(body, "body")));
    }

    /**
     * 租户对应的账本分区名。
     *
     * @param tenantId 租户
     * @return 分区名（{@code t_<tenantId>}）
     * @throws NullPointerException {@code tenantId} 为 {@code null}
     */
    public static String partitionOf(UUID tenantId) {
        return "t_" + Objects.requireNonNull(tenantId, "tenantId");
    }

    private static String requiredHash(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!HASH.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be 64 lowercase hex chars");
        }
        return value;
    }
}
