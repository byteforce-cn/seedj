package cn.byteforce.seedj.ai;

/**
 * 外部副作用的两段状态：先固化 {@code prepared}，执行后只追加 {@code confirmed}/{@code failed}。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum ExternalEffectState {

    /** 已固化意图，尚未调用外部系统（或上次调用结果未知）。 */
    PREPARED("prepared"),

    /** 外部系统已确认。 */
    CONFIRMED("confirmed"),

    /** 外部系统明确失败。 */
    FAILED("failed"),

    /** 结果未知：需要按 {@code decision_id} 核对。 */
    UNKNOWN("unknown");

    private final String wire;

    ExternalEffectState(String wire) {
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
     * @return 状态
     * @throws IllegalArgumentException 未知状态
     */
    public static ExternalEffectState fromWire(String value) {
        for (ExternalEffectState state : values()) {
            if (state.wire.equals(value)) {
                return state;
            }
        }
        throw new IllegalArgumentException("unknown external effect state: " + value);
    }
}
