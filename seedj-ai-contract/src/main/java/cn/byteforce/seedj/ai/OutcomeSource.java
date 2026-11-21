package cn.byteforce.seedj.ai;

/**
 * 结局的来源：运行时上报、决策面自己判定，还是事后核对。
 *
 * <p>三者都追加 outcome 语义的记录，但来源决定了可信度：核对结果可以覆盖“结果未知”的判断， 不能覆盖运行时已观测到的成功或失败。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum OutcomeSource {

    /** 运行时执行后直接上报。 */
    RUNTIME_REPORTED("runtime_reported"),

    /** 决策面自己的结论：拒绝、无需执行（非 {@code execute_tool}）、签发失败——没有执行侧参与。 */
    PLANE_DECIDED("plane_decided"),

    /** 事后与外部系统核对得出。 */
    RECONCILED("reconciled");

    private final String wire;

    OutcomeSource(String wire) {
        this.wire = wire;
    }

    /**
     * 线格式名称。
     *
     * @return 小写来源名
     */
    public String wire() {
        return wire;
    }
}
