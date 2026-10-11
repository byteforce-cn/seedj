package cn.byteforce.seedj.starter.eis.authz;

import java.time.Instant;

/**
 * 双时间轴事实基类（principal / role / binding / proxy 共享）。
 *
 * <p>半开区间命中（与 {@code tools/authz-resolver} 一致）：业务轴
 * {@code valid_from <= as_of < valid_to}；给出 recorded_as_of 时叠加系统轴
 * {@code recorded_at <= recorded_as_of < superseded_at}。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public abstract class EisAuthzFact {

    private final Instant validFrom;

    private final Instant validTo;

    private final Instant recordedAt;

    private final Instant supersededAt;

    /**
     * 构造事实。
     *
     * @param validFrom    业务生效时间（可空 = 与无穷）
     * @param validTo      业务失效时间（可空 = 当前有效）
     * @param recordedAt   系统记录时间（可空 = 与无穷）
     * @param supersededAt 系统替代时间（可空 = 当前记录）
     */
    protected EisAuthzFact(Instant validFrom, Instant validTo, Instant recordedAt, Instant supersededAt) {
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.recordedAt = recordedAt;
        this.supersededAt = supersededAt;
    }

    /**
     * 双时间轴命中判定。
     *
     * @param asOf         业务时点（必填）
     * @param recordedAsOf 系统时点（可空）
     * @return 命中为 {@code true}
     */
    public boolean inRange(Instant asOf, Instant recordedAsOf) {
        if (validFrom != null && validFrom.isAfter(asOf)) {
            return false;
        }
        if (validTo != null && !validTo.isAfter(asOf)) {
            return false;
        }
        if (recordedAsOf != null) {
            if (recordedAt != null && recordedAt.isAfter(recordedAsOf)) {
                return false;
            }
            if (supersededAt != null && !supersededAt.isAfter(recordedAsOf)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 获取业务生效时间。
     *
     * @return valid_from；可空
     */
    public Instant getValidFrom() {
        return validFrom;
    }

    /**
     * 获取业务失效时间。
     *
     * @return valid_to；可空
     */
    public Instant getValidTo() {
        return validTo;
    }

    /**
     * 获取系统记录时间。
     *
     * @return recorded_at；可空
     */
    public Instant getRecordedAt() {
        return recordedAt;
    }

    /**
     * 获取系统替代时间。
     *
     * @return superseded_at；可空
     */
    public Instant getSupersededAt() {
        return supersededAt;
    }
}
