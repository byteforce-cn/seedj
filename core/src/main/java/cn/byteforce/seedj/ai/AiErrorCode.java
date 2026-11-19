package cn.byteforce.seedj.ai;

/**
 * AI 命令网关的守卫错误码：守卫拒绝时的稳定标识，出现在 token 校验、决策面与账本结局里。
 *
 * <p>线格式为小写下划线名，冻结后不要重命名：它是调用方与运维告警的匹配依据。新增拒绝原因时追加常量， 不要复用既有 code 表达不同含义。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum AiErrorCode {

    /** 决策 token 不合法（签名、结构或必填 claim 缺失）。 */
    TOKEN_INVALID("token_invalid"),

    /** 决策 token 已过期。 */
    TOKEN_EXPIRED("token_expired"),

    /** token 里的主体/被代理人与请求不一致。 */
    IDENTITY_MISMATCH("identity_mismatch"),

    /** 命令类型未在工具注册表登记。 */
    COMMAND_NOT_REGISTERED("command_not_registered"),

    /** token 里的命令引用与请求不一致。 */
    COMMAND_MISMATCH("command_mismatch"),

    /** 目标对象集合不一致。 */
    TARGETS_MISMATCH("targets_mismatch"),

    /** 期望版本不一致。 */
    VERSION_MISMATCH("version_mismatch"),

    /** 参数指纹不一致。 */
    ARGS_HASH_MISMATCH("args_hash_mismatch"),

    /** 动作分类不一致（token 声明的等级低于实际命令）。 */
    ACTION_CLASS_MISMATCH("action_class_mismatch"),

    /** 该动作分类要求审批，但 token 未携带审批快照。 */
    APPROVAL_MISSING("approval_missing"),

    /** token 受众与服务不一致。 */
    AUD_MISMATCH("aud_mismatch"),

    /** 幂等键与 token 绑定的幂等指纹不一致。 */
    IDEMPOTENCY_KEY_MISMATCH("idempotency_key_mismatch"),

    /** 策略判定已过期，需要重新评估。 */
    POLICY_STALE("policy_stale"),

    /** 决策账本不可用：无法写意图或无法核对。 */
    LEDGER_UNAVAILABLE("ledger_unavailable"),

    /** 策略层没有可用结果：没有求值引用，或本服务没有该策略包的 head。 */
    POLICY_UNAVAILABLE("policy_unavailable");

    private final String wire;

    AiErrorCode(String wire) {
        this.wire = wire;
    }

    /**
     * 线格式名称。
     *
     * @return 小写下划线错误码
     */
    public String wire() {
        return wire;
    }

    /**
     * 解析线格式名称。
     *
     * @param value 小写下划线错误码
     * @return 对应的错误码
     * @throws IllegalArgumentException {@code value} 不是已冻结的线格式名
     */
    public static AiErrorCode fromWire(String value) {
        for (AiErrorCode code : values()) {
            if (code.wire.equals(value)) {
                return code;
            }
        }
        throw new IllegalArgumentException("unknown ai error code: " + value);
    }
}
