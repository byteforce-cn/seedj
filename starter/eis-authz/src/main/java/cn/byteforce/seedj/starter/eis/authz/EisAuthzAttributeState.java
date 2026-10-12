package cn.byteforce.seedj.starter.eis.authz;

/**
 * 属性快照状态（对齐 {@code attributes.api.yaml} 的 {@code attribute_state}
 * 与 {@code attribute.rules.yaml} snapshot_lifecycle）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum EisAuthzAttributeState {

    /** 快照在有效期内，可用于条件求值。 */
    FRESH("fresh"),

    /** 快照已过期：仅可用于审计/对比，不得放行。 */
    STALE("stale"),

    /** 该 principal + as_of 无快照：fail-closed。 */
    MISSING("missing"),

    /** 属主不可达或超时：fail-closed。 */
    SOURCE_UNAVAILABLE("source_unavailable"),

    /** 本次判定不依赖属性（无属性条件）：不触发拉取。 */
    NOT_REQUIRED("not_required");

    private final String code;

    EisAuthzAttributeState(String code) {
        this.code = code;
    }

    /**
     * 获取契约字面量。
     *
     * @return 契约字面量（如 {@code fresh}）
     */
    public String getCode() {
        return code;
    }

    /**
     * 按契约字面量解析状态。
     *
     * @param code 契约字面量
     * @return 状态；输入为空（null/blank）时为 {@code null}
     * @throws IllegalArgumentException 未知字面量
     */
    public static EisAuthzAttributeState fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (EisAuthzAttributeState state : values()) {
            if (state.code.equals(code)) {
                return state;
            }
        }
        throw new IllegalArgumentException("未知 attribute_state: " + code);
    }
}
