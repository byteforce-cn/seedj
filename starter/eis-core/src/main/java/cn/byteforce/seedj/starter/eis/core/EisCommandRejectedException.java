package cn.byteforce.seedj.starter.eis.core;

/**
 * 命令拒绝异常：规则链 / 命令处理器在受理阶段判定的业务拒绝。
 *
 * <p>语义对齐参考内核 {@code Rejected}：携带错误码（如 {@code IAM-409-001}）与
 * 人类可读原因，可选机器可读原因码（如 SoD 规则 ID）。运行时捕获本异常后，
 * 命令记录以 {@code rejected} 终态落库（不写事件、不入审计链——核心 04 语义：
 * 受理才入链），并以 400/403/404/409 形状响应调用方。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class EisCommandRejectedException extends RuntimeException {

    private final String code;

    private final String reasonCode;

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
        super(reason);
        this.code = code;
        this.reasonCode = reasonCode;
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
}
