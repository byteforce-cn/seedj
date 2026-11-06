package cn.byteforce.seedj.read;

import java.util.Objects;

/**
 * 单个字段的访问结果。
 *
 * @param action   动作
 * @param policyId 贡献该结果的策略 id；无策略时为 {@code null}
 * @author ByteForce
 * @since 1.0.0
 */
public record FieldAccess(Action action, String policyId) {

    /** 字段访问动作。 */
    public enum Action {

        /** 完整值。 */
        FULL,

        /** 打码值。 */
        MASKED,

        /** 不输出。 */
        HIDDEN
    }

    public FieldAccess {
        action = Objects.requireNonNull(action, "action");
    }
}
