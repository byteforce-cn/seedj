package cn.byteforce.seedj.workflow.spi;

/**
 * Provider 能力声明（v2.4：21 项）。
 *
 * <p>调用方<b>必须</b>在调用可选能力前检查本记录；Provider 声明与实现不一致视为缺陷。
 * {@code supportsProviderClock} 为 {@code true} 时 {@link WorkflowProvider#now()} 必须返回
 * Provider 内部时钟；旧 Provider 返回 {@code false} 时仍可使用默认的 {@code Instant.now()}。
 */
public record WorkflowCapabilities(
        // 技术性能力
        boolean supportsCompensation,
        boolean supportsSearch,
        boolean supportsHistory,
        boolean supportsVersioning,
        boolean supportsSignalIdempotency,

        // 审批基础
        boolean supportsHumanTasks,
        boolean supportsRejectToStep,

        // 动态操作
        boolean supportsAddAssignee,
        boolean supportsAddAssigneeBefore,
        boolean supportsAddAssigneeAfter,
        boolean supportsRemoveAssignee,
        boolean supportsTransfer,
        boolean supportsDelegate,
        boolean supportsWithdraw,
        boolean supportsWithdrawInstance,
        boolean supportsUrge,
        boolean supportsCc,

        // 表单与参与者
        boolean supportsDynamicForm,
        boolean supportsParticipantResolution,

        // v2.4 新增
        boolean supportsProviderClock,
        boolean supportsResumeWorkflow
) {
    /** 技术性工作流基础能力（无人工任务）。 */
    public static WorkflowCapabilities basic() {
        return new WorkflowCapabilities(
                false, false, false, true, true,
                false, false,
                false, false, false, false, false, false, false, false, false, false,
                false, false,
                false, false
        );
    }

    /** 支持基本人工任务但不含动态操作。 */
    public static WorkflowCapabilities humanTasks() {
        return new WorkflowCapabilities(
                false, true, true, true, true,
                true, false,
                false, false, false, false, false, false, false, false, false, false,
                false, false,
                false, false
        );
    }

    /** 完整审批能力。 */
    public static WorkflowCapabilities approval() {
        return new WorkflowCapabilities(
                true, true, true, true, true,
                true, true,
                true, true, true, true, true, true, true, true, true, true,
                true, true,
                true, true
        );
    }
}
