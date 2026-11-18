package cn.byteforce.seedj.ai;

/**
 * 决策步的类型：agent 每一步只做一件事，类型决定后续校验口径。
 *
 * <p>线格式为小写名（{@code execute_tool} 等），是 token claim、账本记录与决策面共用的稳定字符串； Java 枚举名只用于代码内比较。只有 {@link
 * #EXECUTE_TOOL} 步骤携带命令与动作分类。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum DecisionKind {

    /** 只读查询：不改变状态，不需要决策 token 之外的审批。 */
    READ("read"),

    /** 执行一个已注册命令：必须携带动作分类与命令引用。 */
    EXECUTE_TOOL("execute_tool"),

    /** 向用户追问：暂停当前步骤，等待补充信息。 */
    ASK_USER("ask_user"),

    /** 升级到人工：交回审批或人工处理。 */
    ESCALATE("escalate"),

    /** 结束任务：给出最终答复。 */
    FINISH("finish");

    private final String wire;

    DecisionKind(String wire) {
        this.wire = wire;
    }

    /**
     * 线格式名称。
     *
     * @return 小写步骤名
     */
    public String wire() {
        return wire;
    }

    /**
     * 解析线格式名称。
     *
     * @param value 小写步骤名
     * @return 对应的步骤类型
     * @throws IllegalArgumentException {@code value} 不是已冻结的线格式名
     */
    public static DecisionKind fromWire(String value) {
        for (DecisionKind kind : values()) {
            if (kind.wire.equals(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown decision kind: " + value);
    }
}
