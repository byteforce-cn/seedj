package cn.byteforce.seedj.starter.eis.authz;

/**
 * 属性拉取失败异常（携带机器可读原因码：ATTRIBUTE_MISSING / ATTRIBUTE_SOURCE_UNAVAILABLE）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class EisAuthzAttributeSourceException extends RuntimeException {

    private final String reasonCode;

    /**
     * 构造异常。
     *
     * @param reasonCode 原因码
     * @param message    消息
     */
    public EisAuthzAttributeSourceException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    /**
     * 构造异常（含原因）。
     *
     * @param reasonCode 原因码
     * @param message    消息
     * @param cause      原因
     */
    public EisAuthzAttributeSourceException(String reasonCode, String message, Throwable cause) {
        super(message, cause);
        this.reasonCode = reasonCode;
    }

    /**
     * 无快照（属主 409）。
     *
     * @param principalRef 主体引用
     * @return 异常（ATTRIBUTE_MISSING）
     */
    public static EisAuthzAttributeSourceException missing(String principalRef) {
        return new EisAuthzAttributeSourceException("ATTRIBUTE_MISSING",
                "主体在 as_of 无属性快照: " + principalRef);
    }

    /**
     * 属主不可达/超时（503）。
     *
     * @param principalRef 主体引用
     * @param cause        原因（可空）
     * @return 异常（ATTRIBUTE_SOURCE_UNAVAILABLE）
     */
    public static EisAuthzAttributeSourceException unavailable(String principalRef, Throwable cause) {
        return new EisAuthzAttributeSourceException("ATTRIBUTE_SOURCE_UNAVAILABLE",
                "属性属主不可达: " + principalRef, cause);
    }

    /**
     * 获取原因码。
     *
     * @return ATTRIBUTE_MISSING / ATTRIBUTE_SOURCE_UNAVAILABLE
     */
    public String getReasonCode() {
        return reasonCode;
    }
}
