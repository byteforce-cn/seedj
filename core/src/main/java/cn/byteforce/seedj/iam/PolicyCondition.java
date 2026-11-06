package cn.byteforce.seedj.iam;


import java.util.List;
import java.util.Objects;

/**
 * Small typed ABAC expression AST. It deliberately has no scripting, reflection,
 * or method invocation; extending it requires a code change and a review.
 */
public sealed interface PolicyCondition
        permits PolicyCondition.Always, PolicyCondition.And, PolicyCondition.Or,
                PolicyCondition.Not, PolicyCondition.Compare, PolicyCondition.Exists {

    /** Matches everything; used by policies with no condition. */
    enum Always implements PolicyCondition {
        INSTANCE
    }

    record And(List<PolicyCondition> conditions) implements PolicyCondition {
        public And {
            conditions = List.copyOf(Objects.requireNonNull(conditions, "conditions"));
            if (conditions.isEmpty()) {
                throw new IllegalArgumentException("and requires at least one condition");
            }
        }
    }

    record Or(List<PolicyCondition> conditions) implements PolicyCondition {
        public Or {
            conditions = List.copyOf(Objects.requireNonNull(conditions, "conditions"));
            if (conditions.isEmpty()) {
                throw new IllegalArgumentException("or requires at least one condition");
            }
        }
    }

    record Not(PolicyCondition condition) implements PolicyCondition {
        public Not {
            Objects.requireNonNull(condition, "condition");
        }
    }

    record Compare(PolicyOperand left, PolicyComparisonOperator operator, PolicyOperand right)
            implements PolicyCondition {
        public Compare {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(right, "right");
        }
    }

    record Exists(PolicyOperand operand) implements PolicyCondition {
        public Exists {
            Objects.requireNonNull(operand, "operand");
        }
    }
}
