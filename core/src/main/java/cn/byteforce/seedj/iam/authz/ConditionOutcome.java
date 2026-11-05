package cn.byteforce.seedj.iam.authz;

/**
 * 条件求值结果。
 *
 * @param matched    是否满足
 * @param reasonCode 不满足时的原因码（ATTRIBUTE_MISSING / ATTRIBUTE_MISMATCH）
 * @param attribute  不满足时的属性路径
 * @author ByteForce
 * @since 1.0.0
 */
public record ConditionOutcome(boolean matched, String reasonCode, String attribute) {

    public static ConditionOutcome success() {
        return new ConditionOutcome(true, null, null);
    }

    public static ConditionOutcome mismatch(String attribute) {
        return new ConditionOutcome(false, "ATTRIBUTE_MISMATCH", attribute);
    }

    public static ConditionOutcome missing(String attribute) {
        return new ConditionOutcome(false, "ATTRIBUTE_MISSING", attribute);
    }
}
