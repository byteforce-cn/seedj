package cn.byteforce.seedj.workflow.spi;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 抄送：独立动作，不改状态机。
 *
 * <p>{@code operator} 必须显式声明且非空白；调用方负责认证。 任务级抄送（{@code stepName != null}）要求该步骤 {@code allowCc ==
 * true}； 实例级抄送只要求 {@code capabilities().supportsCc}。
 */
public record CcAction(
        String actionId,
        String operator,
        List<ParticipantSpec> recipients,
        String stepName,
        String message,
        Instant occurredAt)
        implements WorkflowAction {
    public CcAction {
        Objects.requireNonNull(actionId, "actionId cannot be null");
        Objects.requireNonNull(operator, "operator cannot be null");
        if (operator.isBlank()) throw new IllegalArgumentException("operator cannot be blank");
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
        if (recipients.isEmpty()) throw new IllegalArgumentException("recipients cannot be empty");
    }
}
