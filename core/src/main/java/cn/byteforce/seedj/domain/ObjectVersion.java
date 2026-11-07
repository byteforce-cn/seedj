package cn.byteforce.seedj.domain;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * 业务对象版本（双时间轴元结构，10 字段）。
 *
 * <p>业务时间 {@code [validFrom, validTo)} 表示事实在现实中生效的区间； 系统时间 {@code [recordedAt, supersededAt)}
 * 表示系统记录该事实的区间。 两个区间都是半开区间，端点为 {@code null} 表示直到无穷。时间一律 UTC、微秒精度。
 *
 * <p>{@link #isCurrent()} 要求两个轴的右端都为空；仅 {@code validTo} 非空的行 表示业务上已失效，但系统上仍是当前已知版本。
 *
 * @param id 对象 ID（UUIDv7）
 * @param version 乐观锁版本，从 1 起、每次写 +1
 * @param validFrom 业务生效时间
 * @param validTo 业务失效时间，{@code null} 表示当前有效
 * @param recordedAt 系统记录时间
 * @param supersededAt 系统替代时间，{@code null} 表示当前记录
 * @param createdBy 创建者
 * @param createdAt 创建时间
 * @param updatedBy 最后更新者，创建版本为 {@code null}
 * @param updatedAt 最后更新时间，创建版本为 {@code null}
 * @author ByteForce
 * @since 1.0.0
 */
public record ObjectVersion(
        UUID id,
        long version,
        Instant validFrom,
        Instant validTo,
        Instant recordedAt,
        Instant supersededAt,
        UUID createdBy,
        Instant createdAt,
        UUID updatedBy,
        Instant updatedAt) {

    public ObjectVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1, got: " + version);
        }
        validFrom = micros(validFrom);
        validTo = micros(validTo);
        recordedAt = micros(recordedAt);
        supersededAt = micros(supersededAt);
        createdAt = micros(createdAt);
        updatedAt = micros(updatedAt);
        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw new ContractException(
                    ContractErrorCode.TEMPORAL_VIOLATION,
                    "valid_to must be after valid_from (half-open interval)");
        }
        if (supersededAt != null && !supersededAt.isAfter(recordedAt)) {
            throw new ContractException(
                    ContractErrorCode.TEMPORAL_VIOLATION,
                    "superseded_at must be after recorded_at (half-open interval)");
        }
    }

    /**
     * 是否为两个时间轴都开放的当前版本。
     *
     * @return {@code true} 表示当前记录
     */
    public boolean isCurrent() {
        return validTo == null && supersededAt == null;
    }

    /**
     * 业务时点是否落在有效区间内（半开）。
     *
     * @param instant 业务时点
     * @return 命中为 {@code true}
     */
    public boolean validAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !instant.isBefore(validFrom) && (validTo == null || instant.isBefore(validTo));
    }

    /**
     * 系统时点是否落在记录区间内（半开）。
     *
     * @param instant 系统时点
     * @return 命中为 {@code true}
     */
    public boolean recordedAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !instant.isBefore(recordedAt)
                && (supersededAt == null || instant.isBefore(supersededAt));
    }

    /**
     * 返回封口到指定系统时间的副本。
     *
     * @param instant 替代时间，必须晚于 {@code recordedAt}
     * @return 封口后的副本
     */
    public ObjectVersion withSupersededAt(Instant instant) {
        return new ObjectVersion(
                id,
                version,
                validFrom,
                validTo,
                recordedAt,
                Objects.requireNonNull(instant, "instant"),
                createdBy,
                createdAt,
                updatedBy,
                updatedAt);
    }

    private static Instant micros(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }
}
