package cn.byteforce.seedj.starter.eis.core;

import java.util.List;

/**
 * 命令拒绝异常：规则链 / 命令处理器在受理阶段判定的业务拒绝。
 *
 * <p>语义对齐参考内核 {@code Rejected}：携带错误码（如 {@code IAM-409-001}）与
 * 人类可读原因，可选机器可读原因码（如 SoD 规则 ID）与字段级明细
 * （{@link EisCommandRejectionDetail}）。运行时捕获本异常后，
 * 命令记录以 {@code rejected} 终态落库（不写事件、不入审计链——核心 04 语义：
 * 受理才入链），并以 400/403/404/409 形状响应调用方。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class EisCommandRejectedException extends RuntimeException {

    private final String code;

    private final String reasonCode;

    private final List<EisCommandRejectionDetail> details;

    /**
     * 构造命令拒绝。
     *
     * @param code   错误码
     * @param reason 人类可读原因
     */
    public EisCommandRejectedException(String code, String reason) {
        this(code, reason, null);
    }

    /**
     * 构造命令拒绝（带机器可读原因码）。
     *
     * @param code       错误码
     * @param reason     人类可读原因
     * @param reasonCode 机器可读原因码（可空）
     */
    public EisCommandRejectedException(String code, String reason, String reasonCode) {
        this(code, reason, reasonCode, List.of());
    }

    /**
     * 构造命令拒绝（带机器可读原因码与字段级明细）。
     *
     * @param code       错误码
     * @param reason     人类可读原因
     * @param reasonCode 机器可读原因码（可空）
     * @param details    字段级明细（可空/空 = 无字段级定位）
     */
    public EisCommandRejectedException(String code, String reason, String reasonCode,
                                       List<EisCommandRejectionDetail> details) {
        super(reason);
        this.code = code;
        this.reasonCode = reasonCode;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    /**
     * 获取错误码。
     *
     * @return 错误码
     */
    public String getCode() {
        return code;
    }

    /**
     * 获取机器可读原因码。
     *
     * @return 原因码；无则为 {@code null}
     */
    public String getReasonCode() {
        return reasonCode;
    }

    /**
     * 获取字段级明细。
     *
     * @return 明细列表（不可变；无明细时为空列表）
     */
    public List<EisCommandRejectionDetail> getDetails() {
        return details;
    }
}
