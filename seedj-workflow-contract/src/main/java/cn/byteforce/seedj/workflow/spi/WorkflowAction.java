package cn.byteforce.seedj.workflow.spi;

import java.time.Instant;

/** 审批/流程动作统一入口。{@code actionId} 全局幂等；重复调用返回当前实例状态，不重复驱动。 */
public sealed interface WorkflowAction
        permits SignalAction,
                ApproveAction,
                RejectAction,
                AddAssigneeAction,
                RemoveAssigneeAction,
                TransferAction,
                DelegateAction,
                WithdrawAction,
                WithdrawInstanceAction,
                UrgeAction,
                CcAction,
                ResumeWorkflowAction {

    String actionId();

    Instant occurredAt();
}
