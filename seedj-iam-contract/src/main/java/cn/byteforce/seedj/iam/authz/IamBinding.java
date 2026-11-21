package cn.byteforce.seedj.iam.authz;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;

/**
 * 授权绑定：主体 × 角色 × 作用域，带双时间轴。
 *
 * @param id 绑定 ID
 * @param principal 主体 ID
 * @param role 角色 ID
 * @param scope 作用域键值对
 * @param validFrom 业务生效时间（含）
 * @param validTo 业务失效时间（不含）；{@code null} = 永久
 * @param recordedAt 系统记录时间
 * @param supersededAt 系统封口时间；{@code null} = 当前版本
 * @author ByteForce
 * @since 1.0.0
 */
public record IamBinding(
        String id,
        String principal,
        String role,
        Map<String, String> scope,
        Instant validFrom,
        Instant validTo,
        Instant recordedAt,
        Instant supersededAt) {

    public IamBinding {
        id = requireText(id, "id");
        principal = requireText(principal, "principal");
        role = requireText(role, "role");
        scope = scope == null ? Map.of() : Map.copyOf(scope);
        validFrom = truncate(Objects.requireNonNull(validFrom, "validFrom"));
        validTo = truncate(validTo);
        recordedAt = truncate(Objects.requireNonNull(recordedAt, "recordedAt"));
        supersededAt = truncate(supersededAt);
        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw new IllegalArgumentException("validTo must be after validFrom");
        }
    }

    /**
     * 是否在指定业务/系统时点有效。
     *
     * @param asOf 业务时点
     * @param recordedAsOf 系统时点；{@code null} 表示当前认知
     * @return 是否有效
     */
    public boolean effectiveAt(Instant asOf, Instant recordedAsOf) {
        Instant business = truncate(Objects.requireNonNull(asOf, "asOf"));
        if (business.isBefore(validFrom)) {
            return false;
        }
        if (validTo != null && !business.isBefore(validTo)) {
            return false;
        }
        if (recordedAsOf == null) {
            return supersededAt == null;
        }
        Instant recorded = truncate(recordedAsOf);
        if (recordedAt.isAfter(recorded)) {
            return false;
        }
        return supersededAt == null || supersededAt.isAfter(recorded);
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
