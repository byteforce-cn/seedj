package cn.byteforce.seedj.starter.ai.approval;

import cn.byteforce.seedj.workflow.spi.AnyApprove;
import cn.byteforce.seedj.workflow.spi.AnyRejectTerminates;
import cn.byteforce.seedj.workflow.spi.HumanTaskSpec;
import cn.byteforce.seedj.workflow.spi.ParticipantSpec;
import cn.byteforce.seedj.workflow.spi.ParticipantType;
import cn.byteforce.seedj.workflow.spi.RetryPolicy;
import cn.byteforce.seedj.workflow.spi.StepDefinition;
import cn.byteforce.seedj.workflow.spi.StepKind;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinition;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 审批工作流的定义：一个 {@code HUMAN_TASK}（任意一人批准即通过）加一个终态 {@code close} 步骤。
 *
 * <p>定义是纯函数构造的，启动期由 {@link ApprovalService#registerDefinition()} 注册；已存在同 id/version 时
 * 不覆盖，避免重启把正在运行的实例定义换掉。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class ApprovalWorkflowDefinition {

    private ApprovalWorkflowDefinition() {}

    /**
     * 构造审批定义。
     *
     * @param properties 审批配置
     * @param approvers 审批人
     * @return 工作流定义
     * @throws NullPointerException 任一参数为 {@code null}
     * @throws IllegalArgumentException {@code approvers} 为空
     */
    public static WorkflowDefinition definition(
            ApprovalProperties properties, List<UUID> approvers) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(approvers, "approvers");
        if (approvers.isEmpty()) {
            throw new IllegalArgumentException("approvers cannot be empty");
        }
        List<ParticipantSpec> assignees =
                approvers.stream()
                        .map(
                                id ->
                                        new ParticipantSpec(
                                                ParticipantType.USER, id.toString(), Map.of()))
                        .toList();
        HumanTaskSpec humanTask =
                new HumanTaskSpec(
                        assignees,
                        new AnyApprove(),
                        new AnyRejectTerminates(),
                        Set.of("close"),
                        null,
                        Map.of(),
                        false,
                        Set.of(),
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        properties.deadline(),
                        Map.of());
        StepDefinition approval =
                new StepDefinition(
                        "approval",
                        StepKind.HUMAN_TASK,
                        properties.deadline(),
                        RetryPolicy.noRetry(),
                        List.of(),
                        Map.of(),
                        humanTask,
                        null);
        StepDefinition close =
                new StepDefinition(
                        "close",
                        StepKind.ACTIVITY,
                        null,
                        RetryPolicy.noRetry(),
                        List.of("approval"),
                        Map.of());
        return new WorkflowDefinition(
                properties.definitionId(),
                properties.definitionVersion(),
                List.of(approval, close),
                properties.deadline(),
                properties.deadline(),
                Map.of());
    }
}
