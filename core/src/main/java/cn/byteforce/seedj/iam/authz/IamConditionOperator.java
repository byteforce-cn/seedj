package cn.byteforce.seedj.iam.authz;

/**
 * 声明式授权条件运算。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum IamConditionOperator {
    EQ,
    NEQ,
    IN,
    NOT_IN,
    EXISTS
}
