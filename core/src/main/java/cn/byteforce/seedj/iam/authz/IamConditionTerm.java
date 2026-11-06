package cn.byteforce.seedj.iam.authz;

import java.util.List;

/**
 * 条件原子项：属性路径 + 运算 + 候选值。
 *
 * @param attribute 点分属性路径；段可带 {@code [*]}
 * @param op        运算
 * @param values    非空标量候选
 * @author ByteForce
 * @since 1.0.0
 */
public record IamConditionTerm(String attribute, IamConditionOperator op, List<String> values) {

    public IamConditionTerm {
        if (attribute == null || attribute.isBlank()) {
            throw new IllegalArgumentException("attribute must not be blank");
        }
        if (op == null) {
            throw new IllegalArgumentException("op is required");
        }
        values = values == null ? List.of() : List.copyOf(values);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
    }
}
