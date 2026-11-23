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
        awaitAfterAction(instanceId, action);
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
        awaitAfterAction(request.instanceId(), null);
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
        awaitAfterAction(request.instanceId(), null);
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
     * coord 的命令同步提交，但工作流推进由后台驱动完成；{@code GetStatus} 还有 2–3s 的本地
     * 缓存投影，{@code GetInstanceDetail} 才是权威读面。动作返回前等到「detail 的 open 分支
     * 节点」与「状态报告的 currentStateName」一致，或轮次仍开放（票已落库、无需等驱动）。
     */
    private void awaitAfterAction(WorkflowInstanceId instanceId, WorkflowAction action) {
        long capMillis =
                switch (action) {
                    case null -> 10_000;
                    case ApproveAction ignored -> 10_000;
                    case RejectAction ignored -> 10_000;
                    case WithdrawAction ignored -> 10_000;
                    case WithdrawInstanceAction ignored -> 10_000;
                    case ResumeWorkflowAction ignored -> 10_000;
                    case SignalAction ignored -> 10_000;
                    case AddAssigneeAction ignored -> 5_000;
                    default -> 2_000;
                };
        long deadline =
                System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(capMillis);
        String targetRound = targetRound(action);
        int stable = 0;
        while (System.nanoTime() < deadline) {
            try {
                var status = workflows.getStatus(instanceId.value());
                if (status.state().isTerminal()) {
                    return;
                }
                var detail = reads.detailOrNull(instanceId.value());
                if (System.getProperty("workflow.debug") != null) {
                    System.out.println(
                            "DEBUG settle action="
                                    + action
                                    + " state="
                                    + status.state()
                                    + " current="
                                    + status.currentStateName()
                                    + " branches="
                                    + (detail == null
                                            ? "-"
                                            : detail.branches().stream()
                                                    .map(b -> b.node() + ":" + b.status())
                                                    .toList())
                                    + " rounds="
                                    + (detail == null
                                            ? "-"
                                            : detail.rounds().stream()
                                                    .map(r -> r.id() + ":" + r.status())
                                                    .toList())
                                    + " targetOpen="
                                    + roundOpen(detail, targetRound));
                }
                if (settled(action, status, detail, targetRound)) {
                    if (++stable >= 2) {
                        return;
                    }
                } else {
                    stable = 0;
                }
            } catch (CoordException e) {
                if (System.getProperty("workflow.debug") != null) {
                    System.out.println(
                            "DEBUG settle abort action="
                                    + action
                                    + " code="
                                    + e.getErrorCode()
                                    + " msg="
                                    + e.getMessage());
                }
                return;
            }
            sleep(50);
        }
        if (System.getProperty("workflow.debug") != null) {
            System.out.println("DEBUG settle timeout action=" + action);
        }
    }

    private static String targetRound(WorkflowAction action) {
        return switch (action) {
            case ApproveAction approve -> approve.taskId().value();
            case RejectAction reject -> reject.taskId().value();
            case TransferAction transfer -> transfer.taskId().value();
            case DelegateAction delegate -> delegate.taskId().value();
            case WithdrawAction withdraw -> withdraw.taskId().value();
            case AddAssigneeAction add -> add.taskId().value();
            case RemoveAssigneeAction remove -> remove.taskId().value();
            case null, default -> null;
        };
    }

    private static boolean settled(
            WorkflowAction action,
            cn.byteforce.coord.sdk.workflow.WorkflowStatus status,
            cn.byteforce.coord.sdk.workflow.ApprovalInstanceDetail detail,
            String targetRound) {
        boolean consistent = openNode(detail) == null || openNode(detail).equals(status.currentStateName());
        if (action == null || action instanceof WithdrawInstanceAction) {
            // cancel / terminate / 实例撤回：只在终态收口。
            return false;
        }
        if (action instanceof RejectAction || action instanceof WithdrawAction) {
            // 回退驱动：等实例真正落在重开的审批节点上，而不是回退途中的活动节点。
            return openNode(detail) != null && consistent;
        }
        if (action instanceof SignalAction) {
            // 信号恢复 wait/listen：等驱动推进到终态或下一个审批挂起，不能停在 WAITING。
            if (status.state()
                    == cn.byteforce.coord.sdk.workflow.WorkflowState.WAITING) {
                return false;
            }
            if (status.state()
                    == cn.byteforce.coord.sdk.workflow.WorkflowState.SUSPENDED) {
                String liveNode = liveRoundNode(detail);
                return liveNode != null && liveNode.equals(status.currentStateName());
            }
            return false;
        }
        if (action instanceof ApproveAction || action instanceof ResumeWorkflowAction) {
            if (roundOpen(detail, targetRound)) {
                // 票/续期已同步落库，节点仍在收集：不需要等驱动。读面可能落后于命令提交，
                // 所以「轮次仍 open」要带上已计票的证据，否则会把收口前的旧轮次当成已落库。
                return action instanceof ResumeWorkflowAction
                        || roundCounted(detail, targetRound);
            }
            // 目标轮次已收口：必须等驱动推进到下一个节点。分支/轮次在「轮次已通过、
            // 驱动尚未推进」的窗口里仍是旧节点，只看 open 分支会过早返回。
            String targetNode = roundNode(detail, targetRound);
            String liveNode = liveRoundNode(detail);
            if (liveNode != null && !liveNode.equals(targetNode)) {
                return liveNode.equals(status.currentStateName());
            }
            // 下一步不是审批（活动/完成）：等终态或 runtime 进入 WAITING（wait/listen）。
            // GetStatus 是 runtime 的缓存投影：本轮收口后可能仍读到**收口前**的 WAITING。
            // 用 updatedAt 与轮次 closedAt 比较，确认读面已越过本轮收口再认这个 WAITING，
            // 否则会把陈旧的等待态当成「已推进到 listen」，动作返回错误快照。
            if (status.state() != cn.byteforce.coord.sdk.workflow.WorkflowState.WAITING) {
                return false;
            }
            long closedAt = roundClosedAt(detail, targetRound);
            return closedAt <= 0 || status.updatedAt() >= closedAt;
        }
        // 加/减席位、催办、抄送、转办、委托、信号：新轮次出现后等缓存状态追上；
        // 没有状态机变化时两拍即返回。
        return consistent;
    }

    private static boolean roundOpen(
            cn.byteforce.coord.sdk.workflow.ApprovalInstanceDetail detail, String roundId) {
        if (detail == null || roundId == null) {
            return false;
        }
        for (var round : detail.rounds()) {
            if (round.id().equals(roundId)) {
                String value = round.status() == null ? "" : round.status().toLowerCase();
                return value.equals("open") || value.equals("suspended");
            }
        }
        return false;
    }

    /** 轮次里已有席位收口（通过或否决）：读面已看到命令效果的证据。 */
    private static boolean roundCounted(
            cn.byteforce.coord.sdk.workflow.ApprovalInstanceDetail detail, String roundId) {
        if (detail == null || roundId == null) {
            return false;
        }
        return detail.rounds().stream()
                .filter(round -> round.id().equals(roundId))
                .anyMatch(round -> round.completedCount() > 0 || round.rejectedCount() > 0);
    }

    /** 轮次收口时间（ms）；轮次不存在或未收口返回 0。 */
    private static long roundClosedAt(
            cn.byteforce.coord.sdk.workflow.ApprovalInstanceDetail detail, String roundId) {
        if (detail == null || roundId == null) {
            return 0;
        }
        return detail.rounds().stream()
                .filter(round -> round.id().equals(roundId))
                .mapToLong(cn.byteforce.coord.sdk.workflow.ApprovalRound::closedAtMs)
                .findFirst()
                .orElse(0);
    }

    private static String openNode(cn.byteforce.coord.sdk.workflow.ApprovalInstanceDetail detail) {
        if (detail == null) {
            return null;
        }
        for (var branch : detail.branches()) {
            if ("open".equalsIgnoreCase(branch.status())) {
                return branch.node();
            }
        }
        return null;
    }

    /** 仍可收集票的轮次所在节点（open/suspended 且分支 open）；没有则 {@code null}。 */
    private static String liveRoundNode(
            cn.byteforce.coord.sdk.workflow.ApprovalInstanceDetail detail) {
        if (detail == null) {
            return null;
        }
        var round =
                detail.rounds().stream()
                        .filter(
                                candidate ->
                                        "open".equalsIgnoreCase(candidate.status())
                                                || "suspended"
                                                        .equalsIgnoreCase(candidate.status()))
                        .filter(
                                candidate ->
                                        detail.branches().stream()
                                                .anyMatch(
                                                        branch ->
                                                                branch.id()
                                                                                .equals(
                                                                                        candidate
                                                                                                .branchId())
                                                                        && "open"
                                                                                .equalsIgnoreCase(
                                                                                        branch
                                                                                                .status())))
                        .max(java.util.Comparator.comparingLong(
                                cn.byteforce.coord.sdk.workflow.ApprovalRound::openedAtMs))
                        .orElse(null);
        if (round == null) {
            return null;
        }
        String branchId = round.branchId();
        return detail.branches().stream()
                .filter(branch -> branch.id().equals(branchId))
                .map(cn.byteforce.coord.sdk.workflow.ApprovalBranch::node)
                .findFirst()
                .orElse(null);
    }

    private static String roundNode(
            cn.byteforce.coord.sdk.workflow.ApprovalInstanceDetail detail, String roundId) {
        if (detail == null || roundId == null) {
            return null;
        }
        for (var round : detail.rounds()) {
            if (round.id().equals(roundId)) {
                String branchId = round.branchId();
                return detail.branches().stream()
                        .filter(branch -> branch.id().equals(branchId))
                        .map(cn.byteforce.coord.sdk.workflow.ApprovalBranch::node)
                        .findFirst()
                        .orElse(null);
            }
        }
        return null;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
