package cn.byteforce.seedj.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 封口/追加的唯一实现：存储层只负责持久化，不自己计算版本与系统时间。
 *
 * <p>规则：
 *
 * <ul>
 *   <li>创建 → 版本 1，{@code createdBy/createdAt} 取操作者与系统时间；
 *   <li>更正/迟到事实 → 版本 +1，旧行 {@code supersededAt} 置为新行的 {@code recordedAt}；
 *   <li>{@code recordedAt} 严格单调：同一微秒内追加时取旧值 + 1µs，避免 {@code PK(id, recorded_at)} 冲突；
 *   <li>旧行的 {@code valid_*} 从不回改（T-R006/T-R011）。
 * </ul>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class Sealing {

    private static final long MICROS_PER_UNIT = 1L;

    private Sealing() {}

    /**
     * 计算一次追加的新行与被封口的旧行。
     *
     * @param request 追加请求
     * @param current 当前行；创建时为 {@code null}
     * @param now 当前系统时间（UTC，微秒精度）
     * @return 追加结果
     */
    public static AppendResult next(AppendRequest request, TemporalRow current, Instant now) {
        TemporalRules.validateAppend(request, current, now);

        Instant nowMicros = Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
        Instant recordedAt =
                current == null
                        ? nowMicros
                        : max(
                                nowMicros,
                                current.version()
                                        .recordedAt()
                                        .plus(MICROS_PER_UNIT, ChronoUnit.MICROS));
        long version = current == null ? 1L : current.version().version() + 1L;

        UUID createdBy = current == null ? request.actor() : current.version().createdBy();
        Instant createdAt = current == null ? recordedAt : current.version().createdAt();
        UUID updatedBy = current == null ? null : request.actor();
        Instant updatedAt = current == null ? null : recordedAt;

        ObjectVersion next =
                new ObjectVersion(
                        request.id(),
                        version,
                        request.validFrom(),
                        request.validTo(),
                        recordedAt,
                        null,
                        createdBy,
                        createdAt,
                        updatedBy,
                        updatedAt);
        TemporalRules.validate(next);

        Optional<TemporalRow> superseded =
                current == null
                        ? Optional.empty()
                        : Optional.of(
                                new TemporalRow(
                                        current.objectType(),
                                        current.version().withSupersededAt(recordedAt),
                                        current.fields()));

        return new AppendResult(
                new TemporalRow(request.objectType(), next, request.fields()), superseded);
    }

    private static Instant max(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }
}
