package cn.byteforce.seedj.starter.ai.runtime;

/**
 * 一次决策求值的三种结论：放行、拒绝、等待审批。
 *
 * <p>{@link Pending} 不是拒绝，也不是放行：它没有 token，循环必须停下来，等人在审批端点做出决定后用 {@link
 * DecisionClient#grant(java.util.UUID)} 取 token。把 pending 当 allow 会让工具拿到 {@code null} token； 把它当
 * deny 会在日志里留下错误的拒绝原因。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public sealed interface DecisionOutcome {

    /**
     * 放行：执行工具时带 token。
     *
     * @param grant 判定
     */
    record Granted(DecisionGrant grant) implements DecisionOutcome {}

    /**
     * 拒绝：带原因码。
     *
     * @param grant 判定
     */
    record Denied(DecisionGrant grant) implements DecisionOutcome {}

    /**
     * 等待人工审批：没有 token，带 decision_id。
     *
     * @param pending 待审批信息
     */
    record Pending(DecisionPending pending) implements DecisionOutcome {}
}
