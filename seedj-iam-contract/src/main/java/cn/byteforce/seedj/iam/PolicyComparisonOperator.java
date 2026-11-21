package cn.byteforce.seedj.iam;

/** Typed operators only; there is intentionally no scripting escape hatch. */
public enum PolicyComparisonOperator {
    EQ,
    NE,
    IN,
    CONTAINS,
    STARTS_WITH,
    ENDS_WITH,
    GT,
    LT,
    GE,
    LE
}
