package cn.byteforce.seedj.workflow.spi;

import java.time.Instant;
import java.util.List;

/**
 * 任务查询。
 *
 * <p>{@code pendingFor} 匹配 PENDING assignment（排除 SUSPENDED）， {@code completedBy} 匹配 {@code
 * record.assignee} 或 {@code record.onBehalfOf}； 两者互斥。{@code candidateGroups} 匹配 GROUP/DEPARTMENT
 * 参与者。
 */
public record TaskQuery(
        WorkflowInstanceId instanceId,
        String pendingFor,
        String completedBy,
        List<String> candidateGroups,
        String stepName,
        HumanTaskStatus status,
        Instant dueBefore,
        int limit,
        String cursor) {
    public TaskQuery {
        if (limit <= 0) throw new IllegalArgumentException("limit must be > 0");
        candidateGroups = candidateGroups == null ? List.of() : List.copyOf(candidateGroups);
        if (pendingFor != null && completedBy != null)
            throw new IllegalArgumentException("pendingFor and completedBy are mutually exclusive");
    }
}
