package cn.byteforce.seedj.iam;

import java.util.Objects;

/**
 * Operand of a policy comparison. Paths are dotted, e.g. {@code subject.department} is represented
 * as source SUBJECT + path {@code department}.
 */
public record PolicyOperand(PolicyOperandSource source, String path, Object literal) {

    public PolicyOperand {
        source = Objects.requireNonNull(source, "source");
        path = path == null ? "" : path;
        if (source == PolicyOperandSource.LITERAL && literal == null) {
            throw new IllegalArgumentException("literal operand requires a value");
        }
    }

    public static PolicyOperand subject(String path) {
        return new PolicyOperand(PolicyOperandSource.SUBJECT, path, null);
    }

    public static PolicyOperand resource(String path) {
        return new PolicyOperand(PolicyOperandSource.RESOURCE, path, null);
    }

    public static PolicyOperand action() {
        return new PolicyOperand(PolicyOperandSource.ACTION, "action", null);
    }

    public static PolicyOperand environment(String path) {
        return new PolicyOperand(PolicyOperandSource.ENVIRONMENT, path, null);
    }

    public static PolicyOperand literal(Object value) {
        return new PolicyOperand(PolicyOperandSource.LITERAL, "", value);
    }
}
