package cn.byteforce.seedj.infra.coord.workflow;

import cn.byteforce.coord.sdk.CoordException;

import cn.byteforce.coord.sdk.workflow.ApprovalAssignment;
import cn.byteforce.coord.sdk.workflow.ApprovalInstanceDetail;
import cn.byteforce.coord.sdk.workflow.ApprovalRound;
import cn.byteforce.coord.sdk.workflow.ApprovalTask;
import cn.byteforce.coord.sdk.workflow.WorkflowClient;
import cn.byteforce.seedj.infra.coord.workflow.CoordWorkflowErrors.ActionKind;
import cn.byteforce.seedj.infra.coord.workflow.CoordWorkflowSidecar.ServerClock;
import cn.byteforce.seedj.workflow.spi.AddAssigneeAction;
import cn.byteforce.seedj.workflow.spi.ApproveAction;
import cn.byteforce.seedj.workflow.spi.AssigneeNotAllowedException;
import cn.byteforce.seedj.workflow.spi.CcAction;
import cn.byteforce.seedj.workflow.spi.DelegateAction;
import cn.byteforce.seedj.workflow.spi.HumanTaskNotFoundException;
import cn.byteforce.seedj.workflow.spi.ParticipantSpec;
import cn.byteforce.seedj.workflow.spi.ParticipantType;
import cn.byteforce.seedj.workflow.spi.RejectAction;
import cn.byteforce.seedj.workflow.spi.RejectTargetNotAllowedException;
import cn.byteforce.seedj.workflow.spi.RemoveAssigneeAction;
import cn.byteforce.seedj.workflow.spi.ResumeWorkflowAction;
import cn.byteforce.seedj.workflow.spi.SignalAction;
import cn.byteforce.seedj.workflow.spi.StepDefinition;
import cn.byteforce.seedj.workflow.spi.TransferAction;
import cn.byteforce.seedj.workflow.spi.UrgeAction;
import cn.byteforce.seedj.workflow.spi.WithdrawAction;
import cn.byteforce.seedj.workflow.spi.WithdrawInstanceAction;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinition;
import cn.byteforce.seedj.workflow.spi.WorkflowInstanceId;
import cn.byteforce.seedj.workflow.spi.WorkflowProviderException;
import cn.byteforce.seedj.workflow.spi.WorkflowStateConflictException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 12 个动作 + cancel/terminate → coord SDK。
 *
 * <p>actor 策略：席位动作以 seedj 终端用户 {@code on_behalf_of}（调用方服务须在 agent 白名单内）；
 * 管理动作（加签/减签/终止）以服务主体执行，真实 operator 落 sidecar，审计可还原。
 */
final class CoordWorkflowActions {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final java.util.Set<String> OPEN_SEAT_STATUSES =
            java.util.Set.of("created", "claimed");

    private final WorkflowClient workflows;
    private final CoordWorkflowReads reads;
    private final CoordWorkflowSidecar sidecar;
    private final ServerClock clock;
    private final String signAdmin;

    CoordWorkflowActions(
            WorkflowClient workflows,
            CoordWorkflowReads reads,
            CoordWorkflowSidecar sidecar,
            ServerClock clock,
            String signAdmin) {
        this.workflows = workflows;
        this.reads = reads;
        this.sidecar = sidecar;
        this.clock = clock;
        this.signAdmin = signAdmin;
    }

    // ──── 投票类 ────

    void approve(WorkflowInstanceId instanceId, ApproveAction action) {
        ApprovalInstanceDetail detail = detail(instanceId);
        ApprovalRound round = round(detail, action.taskId().value());
        Seat seat = seatFor(detail, round, action.assignee());
        if (!action.formData().isEmpty()) {
            sidecar.putForm(
                    instanceId.value(),
                    round.id(),
                    action.actionId(),
                    action.occurredAt(),
                    action.formData());
        }
        writeAction(
                instanceId.value(),
                action.actionId(),
                Map.of("operator", action.assignee(), "targetStatus", "APPROVED"));
        invoke(
                ActionKind.VOTE,
                () ->
                        workflows.approveOnBehalfOf(
                                instanceId.value(),
                                seat.id(),
                                action.comment(),
                                action.actionId(),
                                action.assignee()));
    }

    void reject(WorkflowInstanceId instanceId, RejectAction action) {
        ApprovalInstanceDetail detail = detail(instanceId);
        ApprovalRound round = round(detail, action.taskId().value());
        Seat seat = seatFor(detail, round, action.assignee());
        WorkflowDefinition definition = reads.definitionOf(detail);
        StepDefinition step = CoordWorkflowReads.stepOf(definition, roundNode(detail, round));
        if (step == null
                || !step.humanTask().rejectTargets().contains(action.targetStep())) {
            throw new RejectTargetNotAllowedException(
                    "reject target '"
                            + action.targetStep()
                            + "' is not in rejectTargets of step "
                            + roundNode(detail, round));
        }
        writeAction(
                instanceId.value(),
                action.actionId(),
                Map.of(
                        "operator",
                        action.assignee(),
                        "targetStep",
                        action.targetStep()));
        int currentIndex = stepIndex(definition, roundNode(detail, round));
        int targetIndex = stepIndex(definition, action.targetStep());
        boolean explicitForward = targetIndex > currentIndex;
        StepDefinition targetStep =
                CoordWorkflowReads.stepOf(definition, action.targetStep());
        boolean targetHasSeats = targetStep != null && targetStep.kind() == cn.byteforce.seedj.workflow.spi.StepKind.HUMAN_TASK;
        boolean nonApprovalTarget = !explicitForward && !targetHasSeats;
        boolean rollbackNow =
                explicitForward
                        || nonApprovalTarget
                        || rollbackNow(step, detail, round, seat, action.targetStep());
        String rollbackTarget = nonApprovalTarget ? roundNode(detail, round) : action.targetStep();
        if (!rollbackNow) {
            invoke(
                    ActionKind.VOTE,
                    () ->
                            workflows.rejectOnBehalfOf(
                                    instanceId.value(),
                                    seat.id(),
                                    action.comment(),
                                    action.actionId(),
                                    action.assignee()));
        } else {
            invoke(
                    ActionKind.VOTE,
                    () ->
                            workflows.rejectToNodeOnBehalfOf(
                                    instanceId.value(),
                                    seat.id(),
                                    rollbackTarget,
                                    action.comment(),
                                    action.actionId(),
                                    action.assignee()));
        }
    }

    void transfer(WorkflowInstanceId instanceId, TransferAction action) {
        requireUserPrincipal(action.toAssignee(), "transfer");
        ApprovalInstanceDetail detail = detail(instanceId);
        ApprovalRound round = round(detail, action.taskId().value());
        Seat seat = seatFor(detail, round, action.fromAssignee());
        invoke(
                ActionKind.VOTE,
                () ->
                        workflows.transferOnBehalfOf(
                                instanceId.value(),
                                seat.id(),
                                action.toAssignee().value(),
                                action.reason(),
                                action.actionId(),
                                action.fromAssignee()));
    }

    void delegate(WorkflowInstanceId instanceId, DelegateAction action) {
        requireUserPrincipal(action.toAssignee(), "delegate");
        ApprovalInstanceDetail detail = detail(instanceId);
        ApprovalRound round = round(detail, action.taskId().value());
        Seat seat = seatFor(detail, round, action.fromAssignee());
        long delegateForMs = action.delegateFor().toMillis();
        invoke(
                ActionKind.VOTE,
                () ->
                        workflows.delegateOnBehalfOf(
                                instanceId.value(),
                                seat.id(),
                                action.toAssignee().value(),
                                action.reason(),
                                delegateForMs,
                                action.actionId(),
                                action.fromAssignee()));
    }

    void signal(WorkflowInstanceId instanceId, SignalAction action) {
        byte[] payload =
                action.payload().isEmpty()
                        ? new byte[0]
                        : MAPPER.writeValueAsBytes(action.payload());
        invoke(
                ActionKind.ADMIN,
                () ->
                        workflows.signal(
                                instanceId.value(),
                                action.signalName(),
                                payload,
                                action.actionId()));
    }

    // ──── 动态动作 ────

    void addAssignee(WorkflowInstanceId instanceId, AddAssigneeAction action) {
        ApprovalInstanceDetail detail = detail(instanceId);
        ApprovalRound round = round(detail, action.taskId().value());
        String node = roundNode(detail, round);
        StepDefinition step = requireStep(detail, node);
        if (!step.humanTask().allowAddAssignee()
                || !step.humanTask()
                        .allowedAddPositions()
                        .contains(action.position())) {
            throw new WorkflowStateConflictException(
                    "add-assignee position " + action.position() + " is not allowed at " + node);
        }
        List<String> approvers = new ArrayList<>();
        for (ParticipantSpec participant : action.newAssignees()) {
            if (participant.type() != ParticipantType.USER) {
                throw new WorkflowProviderException(
                        "add-assignee supports USER participants only; got "
                                + participant.type()
                                + " (resolve groups/roles before calling)");
            }
            approvers.add(participant.value());
        }
        ApprovalTask anchor = anchorSeat(detail, round, action.operator());
        cn.byteforce.coord.sdk.workflow.AddAssigneePosition position =
                switch (action.position()) {
                    case CURRENT -> cn.byteforce.coord.sdk.workflow.AddAssigneePosition.CURRENT;
                    case BEFORE -> cn.byteforce.coord.sdk.workflow.AddAssigneePosition.BEFORE;
                    case AFTER -> cn.byteforce.coord.sdk.workflow.AddAssigneePosition.AFTER;
                };
        ApprovalRound coordRound = round;
        ApprovalTask coordinatorAnchor = anchor;
        String completedAnchor = null;
        if (action.position() == cn.byteforce.seedj.workflow.spi.AddAssigneePosition.AFTER
                && !branchOpen(detail, round.branchId())) {
            // seedj 允许对已完成的原始任务后加签。coord 只接受 open 分支上的 AFTER，
            // 因此把派生轮次挂到当前 open 分支之后（仍保证「下一步之前执行」），
            // 并在 sidecar 记下真正的原始任务，读面据此把 scheduled 轮次投影为待办。
            coordRound = currentOpenRound(detail);
            if (coordRound == null) {
                throw new WorkflowStateConflictException(
                        "after add-assignee on passed task "
                                + action.taskId().value()
                                + " requires an open workflow branch");
            }
            coordinatorAnchor = anchorSeat(detail, coordRound, action.operator());
            completedAnchor = action.taskId().value();
        }
        ApprovalTask anchorSeat =
                action.position() == cn.byteforce.seedj.workflow.spi.AddAssigneePosition.CURRENT
                        ? null
                        : coordinatorAnchor;
        Map<String, String> extras = new LinkedHashMap<>();
        extras.put("operator", action.operator());
        extras.put("position", action.position().name());
        if (completedAnchor != null) {
            extras.put("completedAnchorTaskId", completedAnchor);
        }
        writeAction(instanceId.value(), action.actionId(), extras);
        ApprovalRound targetRound = coordRound;
        invoke(
                ActionKind.ADMIN,
                () ->
                        workflows.addSign(
                                instanceId.value(),
                                targetRound.branchId(),
                                approvers,
                                null,
                                null,
                                action.reason(),
                                action.actionId(),
                                position,
                                anchorSeat == null ? null : anchorSeat.id(),
                                action.actionId()));
    }

    private static boolean branchOpen(ApprovalInstanceDetail detail, String branchId) {
        return detail.branches().stream()
                .anyMatch(
                        branch ->
                                branch.id().equals(branchId)
                                        && "open".equalsIgnoreCase(branch.status()));
    }

    private static ApprovalRound currentOpenRound(ApprovalInstanceDetail detail) {
        ApprovalRound latest = null;
        for (ApprovalRound round : detail.rounds()) {
            if (!"open".equalsIgnoreCase(round.status())
                    || !branchOpen(detail, round.branchId())) {
                continue;
            }
            if (latest == null || round.openedAtMs() >= latest.openedAtMs()) {
                latest = round;
            }
        }
        return latest;
    }

    void removeAssignee(WorkflowInstanceId instanceId, RemoveAssigneeAction action) {
        ApprovalInstanceDetail detail = detail(instanceId);
        ApprovalRound round = round(detail, action.taskId().value());
        String node = roundNode(detail, round);
        StepDefinition step = requireStep(detail, node);
        if (!step.humanTask().allowRemoveAssignee()) {
            throw new WorkflowStateConflictException(
                    "remove-assignee is not allowed at " + node);
        }
        ApprovalTask anchor = anchorSeat(detail, round, action.operator());
        Map<String, String> extras = new LinkedHashMap<>();
        extras.put("operator", action.operator());
        extras.put("removed", String.join(",", action.removedAssigneeIds()));
        writeAction(instanceId.value(), action.actionId(), extras);
        sidecar.markRemoved(instanceId.value(), round.id(), action.removedAssigneeIds());
        invoke(
                ActionKind.ADMIN,
                () ->
                        workflows.removeSign(
                                instanceId.value(),
                                round.branchId(),
                                null,
                                action.reason(),
                                action.actionId(),
                                action.removedAssigneeIds(),
                                anchor.id()));
    }

    void withdrawTask(WorkflowInstanceId instanceId, WithdrawAction action) {
        ApprovalInstanceDetail detail = detail(instanceId);
        ApprovalRound round = round(detail, action.taskId().value());
        ApprovalTask seat = seatWithApproveRecord(detail, round, action.operator());
        // coord 的撤回只能在「已进入的审批节点」上重开分支：活动/未进入的目标没有
        // prior seats（FAILED_PRECONDITION）。此时回退到当前审批节点（可逆投影：
        // sidecar 仍记录真实 targetStep），与驳回的非审批目标同一口径。
        boolean targetEntered =
                detail.branches().stream()
                        .anyMatch(branch -> action.targetStep().equals(branch.node()));
        String rollbackTarget = targetEntered ? action.targetStep() : roundNode(detail, round);
        writeAction(
                instanceId.value(),
                action.actionId(),
                Map.of(
                        "operator",
                        action.operator(),
                        "targetStep",
                        action.targetStep()));
        invoke(
                ActionKind.WITHDRAW,
                () ->
                        workflows.withdrawTaskOnBehalfOf(
                                instanceId.value(),
                                seat.id(),
                                rollbackTarget,
                                action.reason(),
                                action.actionId(),
                                action.operator()));
    }

    void withdrawInstance(WorkflowInstanceId instanceId, WithdrawInstanceAction action) {
        writeAction(
                instanceId.value(),
                action.actionId(),
                Map.of(
                        "operator",
                        action.operator(),
                        "targetStep",
                        action.targetStep(),
                        "withdrawInstance",
                        "true"));
        // coord 0.4.5 的实例撤回带 target_node 时会先提交撤回、再在回退驱动上报
        // 「instance is not suspended on an approval」，实例停在 RUNNING。撤回本身
        // 与回退目标无关：先按无目标撤回（可靠地把实例收口为 CANCELLED），
        // INSTANCE_ROLLED_BACK 由 sidecar 的 targetStep 在读面补齐。
        // coord 0.4.5 的实例撤回会在回退驱动阶段把 workflow 置 FAILED；seedj 要求 CANCELLED。
        // 走工作流 Cancel（审批聚合同事务收口为 CANCELLED），席位/事件的 WITHDRAWN
        // 词汇由读面按 sidecar 的撤回记录补齐（见 known-limitations）。
        invoke(
                ActionKind.WITHDRAW,
                () -> workflows.cancel(instanceId.value(), action.reason(), false));
    }

    void urge(WorkflowInstanceId instanceId, UrgeAction action) {
        ApprovalInstanceDetail detail = detail(instanceId);
        ApprovalRound round = round(detail, action.taskId().value());
        ApprovalTask seat = seatByOwnerOrAny(detail, round, action.operator());
        invoke(
                ActionKind.SEAT,
                () ->
                        workflows.urgeOnBehalfOf(
                                instanceId.value(),
                                seat.id(),
                                action.message(),
                                action.actionId(),
                                action.operator()));
    }

    void cc(WorkflowInstanceId instanceId, CcAction action) {
        List<String> recipients = new ArrayList<>();
        for (ParticipantSpec participant : action.recipients()) {
            recipients.add(participant.value());
        }
        ApprovalInstanceDetail detail = detail(instanceId);
        String seatId = "";
        if (action.stepName() != null) {
            StepDefinition step = requireStep(detail, action.stepName());
            if (!step.humanTask().allowCc()) {
                throw new WorkflowStateConflictException(
                        "cc is not allowed at " + action.stepName());
            }
            ApprovalRound round = latestRoundAt(detail, action.stepName());
            if (round == null) {
                throw new HumanTaskNotFoundException(
                        "no task at step " + action.stepName() + " in " + instanceId.value());
            }
            seatId = anchorSeat(detail, round, action.operator()).id();
        }
        String targetSeat = seatId;
        // 抄送的授权面只认发起人 / sign_admins / 席位持有人；seedj 侧已认证 operator，
        // 这里以服务主体执行（真实 operator 落 sidecar，见 known-limitations）。
        writeAction(instanceId.value(), action.actionId(), Map.of("operator", action.operator()));
        invoke(
                ActionKind.ADMIN,
                () ->
                        workflows.cc(
                                instanceId.value(),
                                targetSeat,
                                recipients,
                                action.message(),
                                action.actionId()));
    }

    void resume(WorkflowInstanceId instanceId, ResumeWorkflowAction action) {
        long dueInMs = action.newDueIn() == null ? 0 : action.newDueIn().toMillis();
        if (dueInMs <= 0) {
            throw new WorkflowStateConflictException("resume requires a positive newDueIn");
        }
        // coord 的 ResumeWorkflow 用 approve.deadline（seedj 的 dueIn）当预算；seedj 的预算
        // 是 StepDefinition.timeout，通常更长。超出 coord 预算时按剩余预算续期，seedj 侧
        // 的可见 dueAt 由 sidecar 保持（见 known-limitations）。
        ApprovalInstanceDetail detail = detail(instanceId);
        long budget = remainingBudget(detail, dueInMs);
        long due =
                budget > 0
                        ? Math.min(dueInMs, Math.max(1, budget - 50))
                        : Math.min(dueInMs, 100);
        sidecar.putAction(
                instanceId.value(),
                action.actionId(),
                Map.of(
                        "operator",
                        action.operator(),
                        "dueInMs",
                        Long.toString(dueInMs)));
        long requested = due;
        invoke(
                ActionKind.ADMIN,
                () ->
                        workflows.resumeWorkflowOnBehalfOf(
                                instanceId.value(),
                                requested,
                                action.reason(),
                                action.actionId(),
                                action.operator()));
    }

    private long remainingBudget(ApprovalInstanceDetail detail, long requested) {
        long now = System.currentTimeMillis();
        long remaining = Long.MAX_VALUE;
        for (ApprovalRound round : detail.rounds()) {
            if ("open".equalsIgnoreCase(round.status()) && round.openedAtMs() > 0) {
                // 席位 deadline 是 coord 的预算来源；取最近 deadline 与当前时间的差。
                long deadline =
                        CoordWorkflowReads.roundSeats(detail, round.id()).stream()
                                .mapToLong(seat -> seat.deadlineMs())
                                .filter(value -> value > 0)
                                .min()
                                .orElse(0);
                if (deadline > 0) {
                    remaining = Math.min(remaining, deadline - now);
                }
            }
        }
        return remaining == Long.MAX_VALUE ? -1 : remaining;
    }

    // ──── 控制面 ────

    void cancel(WorkflowInstanceId instanceId, String reason, boolean compensate) {
        invoke(ActionKind.ADMIN, () -> workflows.cancel(instanceId.value(), reason, compensate));
    }

    void terminate(WorkflowInstanceId instanceId, String reason) {
        invoke(
                ActionKind.ADMIN,
                () ->
                        workflows.terminateOnBehalfOf(
                                instanceId.value(),
                                reason,
                                "seedj:terminate:" + instanceId.value(),
                                signAdmin));
    }

    // ──── 内部 ────

    private ApprovalInstanceDetail detail(WorkflowInstanceId instanceId) {
        ApprovalInstanceDetail detail = reads.detailOrNull(instanceId.value());
        if (detail == null) {
            throw new cn.byteforce.seedj.workflow.spi.WorkflowNotFoundException(
                    "workflow instance not found: " + instanceId.value());
        }
        return detail;
    }

    private ApprovalRound round(ApprovalInstanceDetail detail, String roundId) {
        ApprovalRound round = CoordWorkflowReads.findRound(detail, roundId);
        if (round == null) {
            throw new HumanTaskNotFoundException("task not found: " + roundId);
        }
        return round;
    }

    private String roundNode(ApprovalInstanceDetail detail, ApprovalRound round) {
        return detail.branches().stream()
                .filter(branch -> branch.id().equals(round.branchId()))
                .map(branch -> branch.node())
                .findFirst()
                .orElseThrow(
                        () ->
                                new HumanTaskNotFoundException(
                                        "round " + round.id() + " has no branch"));
    }

    private StepDefinition requireStep(ApprovalInstanceDetail detail, String node) {
        WorkflowDefinition definition = reads.definitionOf(detail);
        StepDefinition step = CoordWorkflowReads.stepOf(definition, node);
        if (step == null || step.humanTask() == null) {
            throw new WorkflowProviderException(
                    "step " + node + " is not an approval step in the registered definition");
        }
        return step;
    }

    /**
     * 是否需要在本票后立即回退：AnyReject 总是；AtLeastRejects 达到阈值时；
     * RecordAndContinue 只在「本轮已无其他未决席位」时（seedj 死锁口径）。
     */
    private static boolean rollbackNow(
            StepDefinition step,
            ApprovalInstanceDetail detail,
            ApprovalRound round,
            Seat seat,
            String targetStep) {
        var completion = step.humanTask().rejectCompletion();
        if (completion instanceof cn.byteforce.seedj.workflow.spi.AnyRejectTerminates) {
            return true;
        }
        List<ApprovalTask> seats = CoordWorkflowReads.roundSeats(detail, round.id());
        long otherOpen =
                seats.stream()
                        .filter(candidate -> !candidate.id().equals(seat.id()))
                        .filter(
                                candidate ->
                                        "created".equalsIgnoreCase(candidate.status())
                                                || "claimed".equalsIgnoreCase(candidate.status()))
                        .count();
        if (completion instanceof cn.byteforce.seedj.workflow.spi.AtLeastRejects rejects) {
            long rejectVotes =
                    seats.stream()
                            .filter(
                                    candidate ->
                                            candidate.votes().stream()
                                                    .anyMatch(
                                                            vote ->
                                                                    "reject"
                                                                            .equalsIgnoreCase(
                                                                                    vote.decision())))
                            .count();
            return rejectVotes + 1 >= rejects.count();
        }
        if (completion instanceof cn.byteforce.seedj.workflow.spi.RecordAndContinue) {
            return otherOpen == 0;
        }
        return true;
    }

    private static int stepIndex(WorkflowDefinition definition, String stepName) {
        if (definition == null) {
            throw new WorkflowProviderException(
                    "cannot resolve step " + stepName + ": definition is not available");
        }
        for (int i = 0; i < definition.steps().size(); i++) {
            if (definition.steps().get(i).stepName().equals(stepName)) {
                return i;
            }
        }
        throw new WorkflowProviderException("step not found in definition: " + stepName);
    }

    private Seat seatFor(ApprovalInstanceDetail detail, ApprovalRound round, String actor) {
        List<ApprovalTask> seats = CoordWorkflowReads.roundSeats(detail, round.id());
        if (seats.isEmpty()) {
            throw new HumanTaskNotFoundException("no seats in round " + round.id());
        }
        Optional<ApprovalTask> direct =
                seats.stream()
                        .filter(
                                seat ->
                                        actor.equals(seat.assignee())
                                                || seat.candidates().contains(actor))
                        .findFirst();
        if (direct.isPresent()) {
            return new Seat(direct.get());
        }
        Optional<ApprovalTask> delegated =
                seats.stream()
                        .filter(
                                seat ->
                                        seat.assignments().stream()
                                                .anyMatch(
                                                        assignment ->
                                                                isPending(assignment)
                                                                        && assignment
                                                                                .principal()
                                                                                .equals(actor)))
                        .findFirst();
        return delegated.map(Seat::new)
                .orElseThrow(
                        () ->
                                new AssigneeNotAllowedException(
                                        actor + " is not allowed to act on " + round.id()));
    }

    private ApprovalTask seatByOwnerOrAny(
            ApprovalInstanceDetail detail, ApprovalRound round, String actor) {
        List<ApprovalTask> seats = CoordWorkflowReads.roundSeats(detail, round.id());
        if (seats.isEmpty()) {
            throw new HumanTaskNotFoundException("no seats in round " + round.id());
        }
        return seats.stream()
                .filter(
                        seat ->
                                actor.equals(seat.assignee())
                                        || seat.candidates().contains(actor))
                .findFirst()
                .orElseGet(
                        () ->
                                seats.stream()
                                        .filter(seat -> OPEN_SEAT_STATUSES.contains(seat.status()))
                                        .findFirst()
                                        .orElse(seats.get(0)));
    }

    private ApprovalTask seatWithApproveRecord(
            ApprovalInstanceDetail detail, ApprovalRound round, String actor) {
        List<ApprovalTask> seats = CoordWorkflowReads.roundSeats(detail, round.id());
        return seats.stream()
                .filter(
                        seat ->
                                actor.equals(seat.assignee())
                                        || seat.votes().stream()
                                                .anyMatch(
                                                        vote ->
                                                                actor.equals(vote.actor())
                                                                        || actor.equals(
                                                                                vote.onBehalfOf())))
                .findFirst()
                .orElseThrow(
                        () ->
                                new AssigneeNotAllowedException(
                                        actor + " is not a participant of " + round.id()));
    }

    private ApprovalTask anchorSeat(
            ApprovalInstanceDetail detail, ApprovalRound round, String operator) {
        List<ApprovalTask> seats = CoordWorkflowReads.roundSeats(detail, round.id());
        if (seats.isEmpty()) {
            throw new HumanTaskNotFoundException("no seats in round " + round.id());
        }
        return seats.stream()
                .filter(seat -> operator != null && operator.equals(seat.assignee()))
                .findFirst()
                .orElseGet(
                        () ->
                                seats.stream()
                                        .filter(
                                                seat ->
                                                        OPEN_SEAT_STATUSES.contains(
                                                                seat.status()))
                                        .findFirst()
                                        .orElse(seats.get(0)));
    }

    private static ApprovalRound latestRoundAt(ApprovalInstanceDetail detail, String node) {
        ApprovalRound latest = null;
        for (ApprovalRound round : detail.rounds()) {
            String roundNode =
                    detail.branches().stream()
                            .filter(branch -> branch.id().equals(round.branchId()))
                            .map(branch -> branch.node())
                            .findFirst()
                            .orElse("");
            if (node.equals(roundNode) && !"Scheduled".equalsIgnoreCase(round.status())) {
                if (latest == null || round.openedAtMs() >= latest.openedAtMs()) {
                    latest = round;
                }
            }
        }
        return latest;
    }

    private static boolean isPending(ApprovalAssignment assignment) {
        return "pending".equalsIgnoreCase(assignment.status())
                || "created".equalsIgnoreCase(assignment.status())
                || "claimed".equalsIgnoreCase(assignment.status());
    }

    private void requireUserPrincipal(ParticipantSpec participant, String action) {
        if (participant.type() != ParticipantType.USER) {
            throw new WorkflowProviderException(
                    action
                            + " supports a single USER principal only; coord does not expand"
                            + " ROLE/GROUP/DEPARTMENT on dynamic actions (coord#454). Got "
                            + participant.type()
                            + "="
                            + participant.value());
        }
    }

    private void writeAction(String instanceId, String actionId, Map<String, String> extras) {
        sidecar.putAction(instanceId, actionId, extras);
    }

    private String invokeResult(ActionKind kind, java.util.function.Supplier<String> call) {
        try {
            String value = call.get();
            clock.observe(Instant.now());
            return value;
        } catch (CoordException e) {
            throw CoordWorkflowErrors.toSeedj(e, kind);
        }
    }

    private void invoke(ActionKind kind, Runnable call) {
        try {
            call.run();
            clock.observe(Instant.now());
        } catch (CoordException e) {
            throw CoordWorkflowErrors.toSeedj(e, kind);
        }
    }

    private record Seat(ApprovalTask task) {
        String id() {
            return task.id();
        }

        boolean isOpen() {
            return OPEN_SEAT_STATUSES.contains(task.status());
        }
    }
}
