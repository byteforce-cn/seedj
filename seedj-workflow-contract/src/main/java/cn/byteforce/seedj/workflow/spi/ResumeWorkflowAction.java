package cn.byteforce.seedj.workflow.spi;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * v2.4 新增：从 {@link WorkflowStatus#WAITING} 恢复实例。
 *
 * <p>行为：
 *
 * <ul>
 *   <li>仅当实例处于 {@link WorkflowStatus#WAITING} 时有效。
 *   <li>重建当前步骤的 {@link HumanTask}（新 {@link TaskId}），{@code assignments} 按 {@link HumanTaskSpec}
 *       重新解析（含动态参与者）。
 *   <li>新任务 {@code dueAt = now() + newDueIn}；实例 {@code WAITING → RUNNING}； 写 {@code
 *       INSTANCE_RESUMED} → {@code TASK_CREATED}。
 *   <li>若 {@code StepDefinition.timeout} 的剩余预算不足以容纳 {@code newDueIn} （即已消耗时间 + newDueIn &gt;
 *       timeout），抛 {@link WorkflowStateConflictException}。
 * </ul>
 */
public record ResumeWorkflowAction(
        String actionId, String operator, Duration newDueIn, String reason, Instant occurredAt)
        implements WorkflowAction {
    public ResumeWorkflowAction {
        Objects.requireNonNull(actionId, "actionId cannot be null");
        Objects.requireNonNull(operator, "operator cannot be null");
        if (operator.isBlank()) throw new IllegalArgumentException("operator cannot be blank");
        if (newDueIn != null && (newDueIn.isNegative() || newDueIn.isZero()))
            throw new IllegalArgumentException("newDueIn must be positive");
    }
}
