package cn.byteforce.seedj.workflow.spi;

import java.time.Instant;
import java.util.Objects;

/**
 * 任务分配记录。
 *
 * <p>v2.4 语义：
 *
 * <ul>
 *   <li>{@code COMPLETED} 必须携带 {@link ApproveRecord}；其余状态不携带。
 *   <li>{@code TRANSFERRED} 不携带 {@code record}；转办审计依赖 {@code WorkflowHistoryEvent.TASK_TRANSFERRED}
 *       的 {@code attributes.fromAssignee/toAssignee}。
 *   <li>{@code DELEGATED} 的 {@code participant} 是<b>被代理人</b>；代理人在新增的独立 {@code PENDING} assignment
 *       中，{@code participant} 是代理人。{@code onBehalfOf} 不在 assignment 层存储，仅在代理人处理时写入 {@link
 *       ApproveRecord#onBehalfOf()}。
 * </ul>
 */
public record TaskAssignment(
        ParticipantSpec participant,
        AssignmentStatus status,
        ApproveRecord record,
        Instant assignedAt,
        Instant resolvedAt,
        String resolvedReason) {
    public TaskAssignment {
        Objects.requireNonNull(participant, "participant cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        if (status == AssignmentStatus.COMPLETED && record == null)
            throw new IllegalArgumentException("COMPLETED assignment requires record");
        if (status != AssignmentStatus.COMPLETED && record != null)
            throw new IllegalArgumentException("non-COMPLETED assignment must not carry record");
    }
}
