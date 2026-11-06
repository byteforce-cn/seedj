package cn.byteforce.seedj.domain;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;

import java.time.Instant;
import java.util.Objects;

/**
 * 双时间轴规则校验（T-R001..T-R011 的 runtime 部分）。
 *
 * <p>DDL 能保证的约束（区间有序、单当前、排他不重叠）由数据库兜底；
 * 本类只校验写入前必须由运行时拒绝的规则：半开区间合法性、迟到事实不得来自未来、
 * 更正必须有当前行。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class TemporalRules {

    private TemporalRules() {
    }

    /**
     * 复核一个已构造的版本（T-R001 四时间字段必备）。
     *
     * @param version 对象版本
     */
    public static void validate(ObjectVersion version) {
        Objects.requireNonNull(version, "version");
        if (version.validFrom() == null || version.recordedAt() == null) {
            throw new ContractException(ContractErrorCode.TEMPORAL_VIOLATION,
                    "valid_from and recorded_at are required (T-R001)");
        }
    }

    /**
     * 校验一次追加请求。
     *
     * @param request 追加请求
     * @param current 当前行；创建时为 {@code null}
     * @param now     当前系统时间
     */
    public static void validateAppend(AppendRequest request, TemporalRow current, Instant now) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(now, "now");
        if (request.validTo() != null && !request.validTo().isAfter(request.validFrom())) {
            throw new ContractException(ContractErrorCode.TEMPORAL_VIOLATION,
                    "valid_to must be after valid_from (half-open interval)");
        }
        if (request.kind() == AppendKind.CREATE && current != null) {
            throw new ContractException(ContractErrorCode.VERSION_CONFLICT,
                    "object already exists: " + request.objectType() + "/" + request.id());
        }
        if (request.kind() == AppendKind.CORRECTION && current == null) {
            throw new ContractException(ContractErrorCode.OBJECT_NOT_FOUND,
                    "object not found: " + request.objectType() + "/" + request.id());
        }
        if (request.kind() == AppendKind.LATE_FACT) {
            if (current == null) {
                throw new ContractException(ContractErrorCode.OBJECT_NOT_FOUND,
                        "object not found: " + request.objectType() + "/" + request.id());
            }
            if (!request.validFrom().isBefore(now)) {
                throw new ContractException(ContractErrorCode.TEMPORAL_VIOLATION,
                        "late fact valid_from must be before now (T-R011)");
            }
        }
        if (current != null) {
            if (!current.objectType().equals(request.objectType())
                    || !current.version().id().equals(request.id())) {
                throw new IllegalArgumentException("current row does not match request target: "
                        + current.objectType() + "/" + current.version().id());
            }
        }
    }
}
