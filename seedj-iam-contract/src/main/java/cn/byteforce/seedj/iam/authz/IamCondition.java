package cn.byteforce.seedj.iam.authz;

import java.util.List;
import java.util.Locale;

/**
 * 声明式授权条件：单层 all / any。
 *
 * @param logic {@code all} 或 {@code any}
 * @param terms 原子项
 * @author ByteForce
 * @since 1.0.0
 */
public record IamCondition(String logic, List<IamConditionTerm> terms) {

    public IamCondition {
        logic = logic == null ? "" : logic.toLowerCase(Locale.ROOT);
        if (!logic.equals("all") && !logic.equals("any")) {
            throw new IllegalArgumentException("logic must be all or any");
        }
        terms = terms == null ? List.of() : List.copyOf(terms);
        if (terms.isEmpty()) {
            throw new IllegalArgumentException("terms must not be empty");
        }
    }
}
