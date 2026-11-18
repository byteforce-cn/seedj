package cn.byteforce.seedj.ai;

/**
 * 决策步的结局：账本与决策面用它区分“做完了”和“根本没做”。
 *
 * <p>{@link #UNKNOWN} 与 {@link #FAILED} 不能合并：前者表示结果未观测到（需要核对），后者表示已观测到失败。 {@link
 * #NOT_ISSUED} 表示命令因检查未通过而从未签发，因此没有外部副作用。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum EffectStatus {

    /** 执行成功。 */
    OK("ok"),

    /** 执行失败（已观测到失败原因）。 */
    FAILED("failed"),

    /** 结果未知：命令可能已送达外部系统，需要核对。 */
    UNKNOWN("unknown"),

    /** 不适用：该步骤不产生命令副作用。 */
    NOT_APPLICABLE("not_applicable"),

    /** 未签发：命令在守卫处被拒绝，从未执行。 */
    NOT_ISSUED("not_issued"),

    /** 被业务拒绝（命令已校验但业务规则不通过）。 */
    REJECTED("rejected");

    private final String wire;

    EffectStatus(String wire) {
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
     * @return 对应的结局状态
     * @throws IllegalArgumentException {@code value} 不是已冻结的线格式名
     */
    public static EffectStatus fromWire(String value) {
        for (EffectStatus status : values()) {
            if (status.wire.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown effect status: " + value);
    }
}
