package cn.byteforce.seedj.infra.local.workflow;

import cn.byteforce.seedj.workflow.spi.TaskId;
import java.time.Instant;

/** 一条委托：原参与者 -> 代理人，到期后按代理人是否处理决定是否恢复。 */
final class Delegation {

    final TaskId taskId;
    final String originalParticipant;
    final String proxyParticipant;
    final Instant expiresAt;
    final String actionId;

    boolean handled;
    boolean cancelled;

    Delegation(
            TaskId taskId,
            String originalParticipant,
            String proxyParticipant,
            Instant expiresAt,
            String actionId) {
        this.taskId = taskId;
        this.originalParticipant = originalParticipant;
        this.proxyParticipant = proxyParticipant;
        this.expiresAt = expiresAt;
        this.actionId = actionId;
    }
}
