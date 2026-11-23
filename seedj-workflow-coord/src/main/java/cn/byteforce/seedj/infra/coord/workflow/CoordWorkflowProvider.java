package cn.byteforce.seedj.infra.coord.workflow;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;
import cn.byteforce.coord.sdk.kv.KvClient;
import cn.byteforce.coord.sdk.workflow.WorkflowClient;
import cn.byteforce.seedj.infra.coord.workflow.CoordWorkflowErrors.ActionKind;
import cn.byteforce.seedj.infra.coord.workflow.CoordWorkflowSidecar.ServerClock;
import cn.byteforce.seedj.workflow.spi.AddAssigneeAction;
import cn.byteforce.seedj.workflow.spi.ApproveAction;
import cn.byteforce.seedj.workflow.spi.CancelWorkflowRequest;
import cn.byteforce.seedj.workflow.spi.CcAction;
import cn.byteforce.seedj.workflow.spi.DelegateAction;
import cn.byteforce.seedj.workflow.spi.HumanTask;
import cn.byteforce.seedj.workflow.spi.ParticipantResolutionContext;
import cn.byteforce.seedj.workflow.spi.ParticipantResolutionException;
import cn.byteforce.seedj.workflow.spi.ParticipantSpec;
import cn.byteforce.seedj.workflow.spi.ParticipantType;
import cn.byteforce.seedj.workflow.spi.RejectAction;
import cn.byteforce.seedj.workflow.spi.RemoveAssigneeAction;
import cn.byteforce.seedj.workflow.spi.ResumeWorkflowAction;
import cn.byteforce.seedj.workflow.spi.SignalAction;
import cn.byteforce.seedj.workflow.spi.StartWorkflowRequest;
import cn.byteforce.seedj.workflow.spi.TaskId;
import cn.byteforce.seedj.workflow.spi.TaskQuery;
import cn.byteforce.seedj.workflow.spi.TerminateWorkflowRequest;
import cn.byteforce.seedj.workflow.spi.TransferAction;
import cn.byteforce.seedj.workflow.spi.UrgeAction;
import cn.byteforce.seedj.workflow.spi.WithdrawAction;
import cn.byteforce.seedj.workflow.spi.WithdrawInstanceAction;
import cn.byteforce.seedj.workflow.spi.WorkflowAction;
import cn.byteforce.seedj.workflow.spi.WorkflowCapabilities;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinition;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionId;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionNotFoundException;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionVersion;
import cn.byteforce.seedj.workflow.spi.WorkflowHistoryEvent;
import cn.byteforce.seedj.workflow.spi.WorkflowInstance;
import cn.byteforce.seedj.workflow.spi.WorkflowInstanceId;
import cn.byteforce.seedj.workflow.spi.WorkflowProvider;
import cn.byteforce.seedj.workflow.spi.WorkflowProviderException;
import cn.byteforce.seedj.workflow.spi.WorkflowSearchQuery;
import cn.byteforce.seedj.workflow.spi.WorkflowVersionConflictException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link WorkflowProvider} 的 coord 门面：校验实例 id、分派动作、返回 describe 快照。
 *
 * <p>15 个方法全部实现，{@code capabilities()} 返回 21 项全 true；{@code close()} 是 no-op
 * （{@link cn.byteforce.coord.sdk.CoordClient} 由 {@code CoordAutoConfiguration} 拥有）。
 */
final class CoordWorkflowProvider implements WorkflowProvider {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final WorkflowClient workflows;
    private final CoordWorkflowIds ids;
    private final CoordWorkflowDefinitions definitions;
    private final CoordWorkflowReads reads;
    private final CoordWorkflowActions actions;
    private final CoordWorkflowSettlement settlement;
    private final ServerClock clock = new ServerClock();

    CoordWorkflowProvider(
            WorkflowClient workflows,
            KvClient kv,
            CoordWorkflowProperties properties,
            String namespace) {
        properties.validate();
        this.workflows = workflows;
        this.ids = new CoordWorkflowIds(namespace);
        WorkflowDefinitionCompiler compiler =
                new WorkflowDefinitionCompiler(
                        ids, properties.getSignAdmin(), properties.getMaxDefinitionBytes());
        this.definitions = new CoordWorkflowDefinitions(workflows, compiler, ids);
        CoordWorkflowSidecar sidecar = new CoordWorkflowSidecar(kv, namespace);
        this.reads = new CoordWorkflowReads(workflows, ids, definitions, sidecar, clock);
        this.actions =
                new CoordWorkflowActions(workflows, reads, sidecar, clock, properties.getSignAdmin());
        this.settlement = new CoordWorkflowSettlement(workflows, reads);
    }

    @Override
    public void register(WorkflowDefinition definition) {
        guard(() -> definitions.register(definition), ActionKind.DEFINITION);
    }

    @Override
    public Optional<WorkflowDefinition> findDefinition(
            WorkflowDefinitionId id, WorkflowDefinitionVersion version) {
        return guard(() -> definitions.find(id, version), ActionKind.READ);
    }

    @Override
    public WorkflowInstance start(StartWorkflowRequest request) {
        ids.requireValidInstanceId(request.instanceId());
        WorkflowDefinition definition =
                guard(
                        () ->
                                definitions
                                        .find(request.definitionId(), request.definitionVersion())
                                        .orElseThrow(
                                                () ->
                                                        new WorkflowDefinitionNotFoundException(
                                                                "workflow definition not found: "
                                                                        + request.definitionId()
                                                                        + "@"
                                                                        + request.definitionVersion())),
                        ActionKind.DEFINITION);
        validateExpressions(definition, request.initialVariables());
        String deploymentId =
                guard(
                        () ->
                                definitions.deploymentId(
                                        request.definitionId(), request.definitionVersion()),
                        ActionKind.DEFINITION);
        byte[] input = MAPPER.writeValueAsBytes(request.initialVariables());
        try {
            workflows.startByDefinitionWithIdOnBehalfOf(
                    deploymentId, request.instanceId().value(), input, request.startedBy());
        } catch (CoordException e) {
            if (e.getErrorCode() == ErrorCode.ALREADY_EXISTS) {
                throw new WorkflowVersionConflictException(
                        "workflow instance "
                                + request.instanceId().value()
                                + " already exists with a different definition or version: "
                                + e.getMessage());
            }
            if (e.getErrorCode() == ErrorCode.NOT_FOUND) {
                throw new WorkflowDefinitionNotFoundException(
                        "workflow definition not found: " + request.definitionId());
            }
            throw CoordWorkflowErrors.toSeedj(e, ActionKind.START);
        }
        awaitDriven(request.instanceId());
        return describe(request.instanceId())
                .orElseThrow(
                        () ->
                                new WorkflowProviderException(
                                        "instance disappeared after start: "
                                                + request.instanceId().value()));
    }

    @Override
    public WorkflowInstance act(cn.byteforce.seedj.workflow.spi.ActionRequest request) {
        WorkflowInstanceId instanceId = request.instanceId();
        WorkflowAction action = request.action();
        switch (action) {
            case ApproveAction approve -> actions.approve(instanceId, approve);
            case RejectAction reject -> actions.reject(instanceId, reject);
            case TransferAction transfer -> actions.transfer(instanceId, transfer);
            case DelegateAction delegate -> actions.delegate(instanceId, delegate);
            case AddAssigneeAction add -> actions.addAssignee(instanceId, add);
            case RemoveAssigneeAction remove -> actions.removeAssignee(instanceId, remove);
            case WithdrawAction withdraw -> actions.withdrawTask(instanceId, withdraw);
            case WithdrawInstanceAction withdraw -> actions.withdrawInstance(instanceId, withdraw);
            case UrgeAction urge -> actions.urge(instanceId, urge);
            case CcAction cc -> actions.cc(instanceId, cc);
            case ResumeWorkflowAction resume -> actions.resume(instanceId, resume);
            case SignalAction signal -> actions.signal(instanceId, signal);
        }
        settlement.await(instanceId, action);
        return describe(instanceId)
                .orElseThrow(
                        () ->
                                new WorkflowProviderException(
                                        "instance disappeared after action: "
                                                + instanceId.value()));
    }

    @Override
    public WorkflowInstance cancel(CancelWorkflowRequest request) {
        actions.cancel(request.instanceId(), request.reason(), request.compensate());
        settlement.await(request.instanceId(), null);
        return describe(request.instanceId())
                .orElseThrow(
                        () ->
                                new WorkflowProviderException(
                                        "instance disappeared after cancel: "
                                                + request.instanceId().value()));
    }

    @Override
    public WorkflowInstance terminate(TerminateWorkflowRequest request) {
        actions.terminate(request.instanceId(), request.reason());
        settlement.await(request.instanceId(), null);
        return describe(request.instanceId())
                .orElseThrow(
                        () ->
                                new WorkflowProviderException(
                                        "instance disappeared after terminate: "
                                                + request.instanceId().value()));
    }

    @Override
    public Optional<WorkflowInstance> describe(WorkflowInstanceId instanceId) {
        try {
            return Optional.of(reads.describe(instanceId));
        } catch (cn.byteforce.seedj.workflow.spi.WorkflowNotFoundException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<WorkflowInstance> search(WorkflowSearchQuery query) {
        return guard(() -> reads.search(query), ActionKind.READ);
    }

    @Override
    public List<WorkflowHistoryEvent> history(WorkflowInstanceId instanceId) {
        return guard(() -> reads.history(instanceId), ActionKind.READ);
    }

    @Override
    public List<HumanTask> listTasks(TaskQuery query) {
        return guard(() -> reads.listTasks(query), ActionKind.READ);
    }

    @Override
    public Optional<HumanTask> describeTask(TaskId taskId) {
        return guard(() -> reads.describeTask(taskId), ActionKind.READ);
    }

    @Override
    public List<ParticipantSpec> resolveParticipants(ParticipantResolutionContext context) {
        if (context == null) {
            throw new ParticipantResolutionException("participant resolution context is null");
        }
        return ParticipantExpressions.resolve(
                context.expression(), ParticipantType.USER, context.variables());
    }

    @Override
    public Instant now() {
        return clock.now();
    }

    @Override
    public WorkflowCapabilities capabilities() {
        return WorkflowCapabilities.approval();
    }

    @Override
    public void close() {
        // client 由 CoordAutoConfiguration 拥有；契约测试也只关闭 provider。
    }

    /**
     * coord 的 Start 先落 PENDING 再由运行时驱动；契约要求返回可读的当前步骤。
     *
     * <p>审批桥先提交审批轮次、运行时后提交「挂起在审批」：只等 currentStateName 会在
     * 两者之间的窗口返回，调用方立刻 approve 会得到「instance is not suspended on an
     * approval」。有 open 轮次时必须等到状态真正落为 SUSPENDED。
     */
    private void awaitDriven(WorkflowInstanceId instanceId) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try {
                var status = workflows.getStatus(instanceId.value());
                if (status.state().isTerminal()) {
                    return;
                }
                boolean named =
                        status.currentStateName() != null
                                && !status.currentStateName().isBlank();
                if (named) {
                    boolean openRound = false;
                    try {
                        openRound =
                                workflows.listTaskGroups(
                                                new cn.byteforce.coord.sdk.workflow
                                                        .TaskQueryFilter(
                                                        instanceId.value(),
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null),
                                                20,
                                                "")
                                        .groups().stream()
                                        .anyMatch(
                                                group ->
                                                        "open"
                                                                .equalsIgnoreCase(
                                                                        group.status()));
                    } catch (CoordException e) {
                        // 审批桥尚未提交首轮：继续等运行时驱动。
                        if (e.getErrorCode() != ErrorCode.NOT_FOUND) {
                            throw CoordWorkflowErrors.toSeedj(e, ActionKind.START);
                        }
                    }
                    boolean terminal = status.state().isTerminal();
                    boolean waiting =
                            status.state()
                                    == cn.byteforce.coord.sdk.workflow.WorkflowState.WAITING;
                    boolean suspended =
                            status.state()
                                    == cn.byteforce.coord.sdk.workflow.WorkflowState
                                            .SUSPENDED;
                    if (terminal || waiting || (openRound && suspended)) {
                        return;
                    }
                }
            } catch (CoordException e) {
                throw CoordWorkflowErrors.toSeedj(e, ActionKind.START);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void validateExpressions(
            WorkflowDefinition definition, Map<String, String> variables) {
        for (var step : definition.steps()) {
            if (step.humanTask() == null) {
                continue;
            }
            for (ParticipantSpec participant : step.humanTask().assignees()) {
                if (participant.type() == ParticipantType.EXPRESSION) {
                    ParticipantExpressions.resolve(
                            participant.value(), ParticipantType.EXPRESSION, variables);
                }
            }
        }
    }

    private <T> T guard(java.util.function.Supplier<T> call, ActionKind kind) {
        try {
            return call.get();
        } catch (CoordException e) {
            throw CoordWorkflowErrors.toSeedj(e, kind);
        }
    }

    private void guard(Runnable call, ActionKind kind) {
        try {
            call.run();
        } catch (CoordException e) {
            throw CoordWorkflowErrors.toSeedj(e, kind);
        }
    }

}
