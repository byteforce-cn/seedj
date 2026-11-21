package cn.byteforce.seedj.ai;

/**
 * 账本记录的类型：一条 decision 上按 intent → (approval) → outcome 顺序追加。
 *
 * <p>{@link #RECONCILED} 是事后核对产生的记录，与 {@link #OUTCOME} 分开存：核对不能改写运行时上报的事实， 只能追加它自己的观察。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum DecisionRecordKind {

    /** 决策意图：agent 决定要做什么。 */
    INTENT("intent"),

    /** 人工审批结果：不可逆动作的前置凭据。 */
    APPROVAL("approval"),

    /** 执行结果：运行时上报的结局。 */
    OUTCOME("outcome"),

    /** 核对结果：事后与外部系统对齐得到的结局。 */
    RECONCILED("reconciled");

    private final String wire;

    DecisionRecordKind(String wire) {
        this.wire = wire;
    }

    /**
     * 线格式名称。
     *
     * @return 小写记录类型名
     */
    public String wire() {
        return wire;
    }

    /**
     * 解析线格式名称。
     *
     * @param value 小写记录类型名
     * @return 对应的记录类型
     * @throws IllegalArgumentException {@code value} 不是已冻结的线格式名
     */
    public static DecisionRecordKind fromWire(String value) {
        for (DecisionRecordKind kind : values()) {
            if (kind.wire.equals(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown decision record kind: " + value);
    }
}
