package cn.byteforce.seedj.ai;

/**
 * 审批状态：外部审批系统的结论在本地账本里的投影。
 *
 * <p>{@link #UNKNOWN} 表示无法确认审批结论（外部系统不可用或返回值不认识）；它不等于 {@link #PENDING}， 守卫必须按“未获批准”处理。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum ApprovalStatus {

    /** 等待处理。 */
    PENDING("pending"),

    /** 已批准。 */
    APPROVED("approved"),

    /** 已拒绝。 */
    REJECTED("rejected"),

    /** 已过期：审批时限耗尽。 */
    EXPIRED("expired"),

    /** 结论未知：不得当作已批准。 */
    UNKNOWN("unknown");

    private final String wire;

    ApprovalStatus(String wire) {
        this.wire = wire;
    }

    /**
     * 线格式名称。
     *
     * @return 小写状态名
     */
    public String wire() {
        return wire;
    }

    /**
     * 解析线格式名称。
     *
     * @param value 小写状态名
     * @return 对应的审批状态
     * @throws IllegalArgumentException {@code value} 不是已冻结的线格式名
     */
    public static ApprovalStatus fromWire(String value) {
        for (ApprovalStatus status : values()) {
            if (status.wire.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown approval status: " + value);
    }
}
