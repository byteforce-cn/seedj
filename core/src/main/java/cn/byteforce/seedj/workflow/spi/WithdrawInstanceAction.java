package cn.byteforce.seedj.workflow.spi;

import java.time.Instant;
import java.util.Objects;

/** 实例级撤回：仅 {@code startedBy} 可发起。 */
public record WithdrawInstanceAction(String actionId, String operator,
                                     String targetStep, String reason,
                                     Instant occurredAt) implements WorkflowAction {
    public WithdrawInstanceAction {
        Objects.requireNonNull(actionId, "actionId cannot be null");
        Objects.requireNonNull(operator, "operator cannot be null");
        Objects.requireNonNull(targetStep, "targetStep cannot be null");
    }
}
