package cn.byteforce.seedj.starter.ai.approval;

/**
 * 审批动作：批准或驳回。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum ApprovalAction {

    /** 批准。 */
    APPROVE("approve"),

    /** 驳回。 */
    REJECT("reject");

    private final String wire;

    ApprovalAction(String wire) {
        this.wire = wire;
    }

    /**
     * 线格式名称。
     *
     * @return {@code approve} 或 {@code reject}
     */
    public String wire() {
        return wire;
    }

    /**
     * 解析线格式名称。
     *
     * @param value 线格式名
     * @return 动作
     * @throws IllegalArgumentException 未知动作
     */
    public static ApprovalAction fromWire(String value) {
        for (ApprovalAction action : values()) {
            if (action.wire.equals(value)) {
                return action;
            }
        }
        throw new IllegalArgumentException("unknown approval action: " + value);
    }
}
