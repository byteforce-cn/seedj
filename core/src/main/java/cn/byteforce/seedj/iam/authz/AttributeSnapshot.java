package cn.byteforce.seedj.iam.authz;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 权威属性快照（主体 + 时点）。
 *
 * @param principal 主体 ID
 * @param asOf 快照业务时点
 * @param recordedAt 系统记录时间
 * @param expiresAt 有效期；{@code null} = 不过期
 * @param source 权威属主
 * @param sourceVersion 属主契约版本
 * @param snapshot 属性集合
 * @author ByteForce
 * @since 1.0.0
 */
public record AttributeSnapshot(
        String principal,
        Instant asOf,
        Instant recordedAt,
        Instant expiresAt,
        String source,
        String sourceVersion,
        Map<String, Object> snapshot) {

    public AttributeSnapshot {
        principal = requireText(principal, "principal");
        asOf = truncate(Objects.requireNonNull(asOf, "asOf"));
        recordedAt = truncate(Objects.requireNonNull(recordedAt, "recordedAt"));
        expiresAt = truncate(expiresAt);
        source = requireText(source, "source");
        snapshot = snapshot == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(snapshot));
    }

    /**
     * 相对请求时点的状态。
     *
     * @param requestedAsOf 请求业务时点
     * @return fresh / stale
     */
    public AttributeState stateAt(Instant requestedAsOf) {
        if (expiresAt == null) {
            return AttributeState.FRESH;
        }
        return truncate(requestedAsOf).isBefore(expiresAt)
                ? AttributeState.FRESH
                : AttributeState.STALE;
    }

    private static Instant truncate(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
