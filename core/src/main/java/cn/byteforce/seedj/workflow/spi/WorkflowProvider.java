package cn.byteforce.seedj.workflow.spi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 工作流 Provider 契约（v2.4 冻结基线，长期二进制边界）。
 *
 * <p>语义要点：
 *
 * <ul>
 *   <li>{@code start} 按 {@code instanceId} 幂等，{@code act} 按 {@code actionId} 幂等。
 *   <li>每次状态转换必须先持久化再返回；调用方不直接写状态。
 *   <li>租约、超时、重试、{@code dueAt}、{@code delegateFor} 全部使用 Provider 时钟。
 *   <li>可选能力必须先查 {@link #capabilities()}。
 *   <li>{@link #close()} 幂等且不破坏运行中实例。
 * </ul>
 */
public interface WorkflowProvider extends AutoCloseable {

    // ---------- 定义管理 ----------
    void register(WorkflowDefinition definition);

    Optional<WorkflowDefinition> findDefinition(
            WorkflowDefinitionId id, WorkflowDefinitionVersion version);

    // ---------- 实例生命周期 ----------
    WorkflowInstance start(StartWorkflowRequest request);

    WorkflowInstance act(ActionRequest request);

    WorkflowInstance cancel(CancelWorkflowRequest request);

    WorkflowInstance terminate(TerminateWorkflowRequest request);

    // ---------- 查询 ----------
    Optional<WorkflowInstance> describe(WorkflowInstanceId instanceId);

    List<WorkflowInstance> search(WorkflowSearchQuery query);

    List<WorkflowHistoryEvent> history(WorkflowInstanceId instanceId);

    List<HumanTask> listTasks(TaskQuery query);

    Optional<HumanTask> describeTask(TaskId taskId);

    // ---------- 参与者解析 ----------
    default List<ParticipantSpec> resolveParticipants(ParticipantResolutionContext context) {
        throw new WorkflowProviderException("participant resolution not supported");
    }

    // ---------- Provider 时钟 ----------
    /**
     * 返回 Provider 当前时间（Provider 时钟）。
     *
     * <p>默认实现返回 {@link Instant#now()}；声明 {@code capabilities().supportsProviderClock == true}
     * 的实现<b>必须覆盖</b>， 返回其内部时钟（数据库时间 / 引擎时钟 / 注入的 Clock）。
     *
     * <p>调用方在需要按 Provider 时钟构造业务时间（如审计对账、{@code delegateFor} 的 相对基准）时使用；不应用于状态变更时间（状态变更时间由 Provider
     * 填充）。
     */
    default Instant now() {
        return Instant.now();
    }

    // ---------- 能力声明 ----------
    default WorkflowCapabilities capabilities() {
        return WorkflowCapabilities.basic();
    }

    // ---------- 生命周期 ----------
    @Override
    void close();
}
