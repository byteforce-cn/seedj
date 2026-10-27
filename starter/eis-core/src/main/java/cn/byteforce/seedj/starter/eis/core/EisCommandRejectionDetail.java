package cn.byteforce.seedj.starter.eis.core;

import java.util.Objects;

/**
 * 命令拒绝明细（契约加法字段 {@code details[]}）：把拒绝定位到具体字段。
 *
 * <p>只承载「字段 + 人类可读消息」，不承载属性值（ATR-007 口径：不回传属性值）；
 * 明细随拒绝响应返回，不落库——幂等重放只回放 {@code code} / {@code reason}
 * （与 {@code reason_code} 同等口径，见 {@code docs/known-limitations.md}）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisCommandRejectionDetail {

    private final String field;

    private final String message;

    /**
     * 构造拒绝明细。
     *
     * @param field   定位字段（如 {@code amount}；不可空）
     * @param message 人类可读消息（不可空）
     * @throws IllegalArgumentException 字段或消息为空
     */
    public EisCommandRejectionDetail(String field, String message) {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("拒绝明细的 field 不能为空");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("拒绝明细的 message 不能为空: " + field);
        }
        this.field = field;
        this.message = message;
    }

    /**
     * 获取定位字段。
     *
     * @return 字段名
     */
    public String getField() {
        return field;
    }

    /**
     * 获取人类可读消息。
     *
     * @return 消息
     */
    public String getMessage() {
        return message;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EisCommandRejectionDetail that)) {
            return false;
        }
        return field.equals(that.field) && message.equals(that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, message);
    }

    @Override
    public String toString() {
        return "EisCommandRejectionDetail{" + field + ": " + message + "}";
    }
}
