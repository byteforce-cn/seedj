package cn.byteforce.seedj.infra.local.workflow;

import cn.byteforce.seedj.workflow.spi.ActionDecision;
import cn.byteforce.seedj.workflow.spi.AddAssigneeAction;
import cn.byteforce.seedj.workflow.spi.AddAssigneePosition;
import cn.byteforce.seedj.workflow.spi.AnyRejectTerminates;
import cn.byteforce.seedj.workflow.spi.ApproveAction;
import cn.byteforce.seedj.workflow.spi.ApproveRecord;
import cn.byteforce.seedj.workflow.spi.AssigneeNotAllowedException;
import cn.byteforce.seedj.workflow.spi.AssignmentStatus;
import cn.byteforce.seedj.workflow.spi.AtLeastRejects;
import cn.byteforce.seedj.workflow.spi.CcAction;
import cn.byteforce.seedj.workflow.spi.DelegateAction;
import cn.byteforce.seedj.workflow.spi.HumanTask;
import cn.byteforce.seedj.workflow.spi.HumanTaskKind;
import cn.byteforce.seedj.workflow.spi.HumanTaskSpec;
import cn.byteforce.seedj.workflow.spi.HumanTaskStateConflictException;
import cn.byteforce.seedj.workflow.spi.HumanTaskStatus;
import cn.byteforce.seedj.workflow.spi.ParticipantSpec;
import cn.byteforce.seedj.workflow.spi.RejectAction;
import cn.byteforce.seedj.workflow.spi.RejectCompletion;
import cn.byteforce.seedj.workflow.spi.RejectTargetNotAllowedException;
import cn.byteforce.seedj.workflow.spi.RemoveAssigneeAction;
import cn.byteforce.seedj.workflow.spi.ResumeWorkflowAction;
import cn.byteforce.seedj.workflow.spi.SignalAction;
import cn.byteforce.seedj.workflow.spi.StepDefinition;
import cn.byteforce.seedj.workflow.spi.StepKind;
import cn.byteforce.seedj.workflow.spi.TaskAssignment;
import cn.byteforce.seedj.workflow.spi.TransferAction;
import cn.byteforce.seedj.workflow.spi.UrgeAction;
import cn.byteforce.seedj.workflow.spi.WithdrawAction;
import cn.byteforce.seedj.workflow.spi.WithdrawInstanceAction;
import cn.byteforce.seedj.workflow.spi.WithdrawNotAllowedException;
import cn.byteforce.seedj.workflow.spi.WorkflowStateConflictException;
import cn.byteforce.seedj.workflow.spi.WorkflowStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 拆分后的内存工作流协作者：InMemoryWorkflowActions。 */
final class InMemoryWorkflowActions {

    private final InMemoryWorkflowSupport support;
    private final InMemoryWorkflowInstanceEngine engine;
    private final InMemoryWorkflowTaskLifecycle lifecycle;
    private final InMemoryWorkflowTimeouts timeouts;

    InMemoryWorkflowActions(
            InMemoryWorkflowSupport support,
            InMemoryWorkflowInstanceEngine engine,
            InMemoryWorkflowTaskLifecycle lifecycle,
            InMemoryWorkflowTimeouts timeouts) {
        this.support = support;
        this.engine = engine;
        this.lifecycle = lifecycle;
        this.timeouts = timeouts;
    }

    void onSignal(InstanceState state, SignalAction action) {
        StepDefinition step = support.requireCurrentStep(state);
        if (step.kind() != StepKind.WAIT_SIGNAL) {
            throw new WorkflowStateConflictException(
                    "step " + step.stepName() + " is not WAIT_SIGNAL");
        }
        if (!Objects.equals(step.waitSignalName(), action.signalName())) {
            throw new WorkflowStateConflictException(
                    "signal '"
                            + action.signalName()
                            + "' does not match waitSignalName '"
                            + step.waitSignalName()
                            + "'");
        }
        state.variables.putAll(action.payload());
        engine.completeStepAndAdvance(state, step.stepName(), action.actionId());
    }

    void onApprove(InstanceState state, ApproveAction action) {
        HumanTask task = support.requirePendingTask(state, action.taskId());
        ProxyRef proxy = support.locateProxy(state, task, action.assignee());
        int index;
        Delegation delegation;
        if (proxy != null) {
            index = proxy.index();
            delegation = proxy.delegation();
        } else {
            index = support.pendingIndex(task, action.assignee());
            delegation = null;
            if (index < 0) {
                throw new AssigneeNotAllowedException(
                        action.assignee()
                                + " is not a pending assignee of task "
                                + task.id().value());
            }
        }
        Instant now = support.now();
        TaskAssignment assignment = task.assignments().get(index);
        String onBehalfOf = delegation == null ? null : delegation.originalParticipant;
        ApproveRecord record =
                new ApproveRecord(
                        action.assignee(),
                        onBehalfOf,
                        ActionDecision.APPROVE,
                        action.comment(),
                        now,
                        Map.of());
        HumanTask updated =
                TaskSupport.replace(
                        task, index, TaskSupport.completed(assignment, record, now), now);
        updated = support.withFormData(updated, action.formData(), now);
        state.tasks.put(updated.id(), updated);
        engine.evaluate(state, updated);
    }

    void onReject(InstanceState state, RejectAction action) {
        HumanTask task = support.requirePendingTask(state, action.taskId());
        HumanTaskSpec spec = support.specOf(state, task);
        if (!spec.rejectTargets().contains(action.targetStep())) {
            throw new RejectTargetNotAllowedException(
                    "targetStep '" + action.targetStep() + "' is not in rejectTargets");
        }
        if (support.requireStep(state, action.targetStep()) == null) {
            throw new RejectTargetNotAllowedException(
                    "targetStep '" + action.targetStep() + "' does not exist");
        }

        int index;
        Delegation delegation;
        ProxyRef proxy = support.locateProxy(state, task, action.assignee());
        if (proxy != null) {
            index = proxy.index();
            delegation = proxy.delegation();
        } else {
            index = support.pendingIndex(task, action.assignee());
            delegation = null;
            if (index < 0) {
                throw new AssigneeNotAllowedException(
                        action.assignee()
                                + " is not a pending assignee of task "
                                + task.id().value());
            }
        }

        Instant now = support.now();
        TaskAssignment assignment = task.assignments().get(index);
        String onBehalfOf = delegation == null ? null : delegation.originalParticipant;
        Map<String, String> extra =
                Map.of(
                        "actionId", action.actionId(),
                        "targetStep", action.targetStep());
        ApproveRecord record =
                new ApproveRecord(
                        action.assignee(),
                        onBehalfOf,
                        ActionDecision.REJECT,
                        action.comment(),
                        now,
                        extra);
        HumanTask updated =
                TaskSupport.replace(
                        task, index, TaskSupport.completed(assignment, record, now), now);
        state.tasks.put(updated.id(), updated);
        state.lastRejectTarget.put(updated.id(), action.targetStep());

        RejectCompletion rule = spec.rejectCompletion();
        if (rule instanceof AnyRejectTerminates) {
            engine.rejectTask(
                    state,
                    updated,
                    action.targetStep(),
                    InMemoryWorkflowSupport.REASON_REJECTED_TERMINATED,
                    action.actionId());
            return;
        }
        if (rule instanceof AtLeastRejects threshold
                && TaskSupport.rejects(updated) >= threshold.count()) {
            engine.rejectTask(
                    state,
                    updated,
                    action.targetStep(),
                    "REJECT_THRESHOLD_REACHED",
                    action.actionId());
            return;
        }
        engine.evaluate(state, updated);
    }

    void onAddAssignee(InstanceState state, AddAssigneeAction action) {
        support.requireCapability(support.capabilities().supportsAddAssignee(), "add-assignee");
        HumanTask task = support.requireTask(state, action.taskId());
        HumanTaskSpec spec = support.specOf(state, task);
        if (!spec.allowAddAssignee()) {
            throw new WorkflowStateConflictException(
                    "add-assignee is not allowed for step " + task.stepName());
        }
        if (!spec.allowedAddPositions().contains(action.position())) {
            throw new AssigneeNotAllowedException(
                    "position "
                            + action.position()
                            + " is not allowed for step "
                            + task.stepName());
        }
        if (action.position() == AddAssigneePosition.BEFORE) {
            support.requireCapability(
                    support.capabilities().supportsAddAssigneeBefore(), "add-assignee BEFORE");
            addBefore(state, task, spec, action);
        } else if (action.position() == AddAssigneePosition.AFTER) {
            support.requireCapability(
                    support.capabilities().supportsAddAssigneeAfter(), "add-assignee AFTER");
            addAfter(state, task, spec, action);
        } else {
            addCurrent(state, task, action);
        }
    }

    void onRemoveAssignee(InstanceState state, RemoveAssigneeAction action) {
        HumanTask task = support.requirePendingTask(state, action.taskId());
        HumanTaskSpec spec = support.specOf(state, task);
        if (!spec.allowRemoveAssignee()) {
            throw new WorkflowStateConflictException(
                    "remove-assignee is not allowed for step " + task.stepName());
        }
        Instant now = support.now();
        List<TaskAssignment> assignments = new ArrayList<>(task.assignments());
        List<String> removed = new ArrayList<>();
        for (String assignee : action.removedAssigneeIds()) {
            int index = support.pendingIndex(task, assignee);
            if (index < 0) {
                throw new WorkflowStateConflictException(
                        assignee + " is not a PENDING assignee of task " + task.id().value());
            }
            removed.add(assignee);
            assignments.set(
                    index,
                    new TaskAssignment(
                            task.assignments().get(index).participant(),
                            AssignmentStatus.CANCELLED,
                            null,
                            task.assignments().get(index).assignedAt(),
                            now,
                            "REMOVED"));
            lifecycle.cancelDelegationForProxy(state, task.id(), assignee);
            support.event(
                    state,
                    "TASK_ASSIGNMENT_AUTO_CANCELLED",
                    task.stepName(),
                    task.id(),
                    action.actionId(),
                    support.attrs("reason", "REMOVED", "participant", assignee));
        }
        HumanTask updated =
                TaskSupport.withAssignments(task, assignments, HumanTaskStatus.PENDING, now, null);
        state.tasks.put(updated.id(), updated);
        support.event(
                state,
                "TASK_ASSIGNEE_REMOVED",
                updated.stepName(),
                updated.id(),
                action.actionId(),
                support.attrs(
                        "operator",
                        support.nullSafe(action.operator()),
                        "assignees",
                        String.join(",", removed)));
        engine.evaluate(state, updated);
    }

    void onTransfer(InstanceState state, TransferAction action) {
        support.requireCapability(support.capabilities().supportsTransfer(), "transfer");
        HumanTask task = support.requirePendingTask(state, action.taskId());
        HumanTaskSpec spec = support.specOf(state, task);
        if (!spec.allowTransfer()) {
            throw new WorkflowStateConflictException(
                    "transfer is not allowed for step " + task.stepName());
        }
        int index = support.pendingIndex(task, action.fromAssignee());
        if (index < 0) {
            throw new AssigneeNotAllowedException(
                    action.fromAssignee()
                            + " is not a PENDING assignee of task "
                            + task.id().value());
        }
        Instant now = support.now();
        List<TaskAssignment> assignments = new ArrayList<>(task.assignments());
        TaskAssignment original = assignments.get(index);
        assignments.set(
                index,
                new TaskAssignment(
                        original.participant(),
                        AssignmentStatus.TRANSFERRED,
                        null,
                        original.assignedAt(),
                        now,
                        null));
        lifecycle.cancelDelegationForProxy(state, task.id(), action.fromAssignee());
        assignments.add(
                new TaskAssignment(
                        action.toAssignee(), AssignmentStatus.PENDING, null, now, null, null));
        HumanTask updated =
                TaskSupport.withAssignments(task, assignments, HumanTaskStatus.PENDING, now, null);
        state.tasks.put(updated.id(), updated);
        support.event(
                state,
                "TASK_TRANSFERRED",
                updated.stepName(),
                updated.id(),
                action.actionId(),
                support.attrs(
                        "fromAssignee",
                        action.fromAssignee(),
                        "toAssignee",
                        action.toAssignee().value(),
                        "reason",
                        support.nullSafe(action.reason())));
    }

    void onDelegate(InstanceState state, DelegateAction action) {
        support.requireCapability(support.capabilities().supportsDelegate(), "delegate");
        HumanTask task = support.requirePendingTask(state, action.taskId());
        HumanTaskSpec spec = support.specOf(state, task);
        if (!spec.allowDelegate()) {
            throw new WorkflowStateConflictException(
                    "delegate is not allowed for step " + task.stepName());
        }
        int index = support.pendingIndex(task, action.fromAssignee());
        if (index < 0) {
            throw new AssigneeNotAllowedException(
                    action.fromAssignee()
                            + " is not a PENDING assignee of task "
                            + task.id().value());
        }
        boolean nested =
                state.delegations.stream()
                        .anyMatch(
                                d ->
                                        !d.handled
                                                && !d.cancelled
                                                && d.taskId.equals(task.id())
                                                && d.proxyParticipant.equals(
                                                        action.fromAssignee()));
        if (nested) {
            throw new WorkflowStateConflictException("nested delegation is not allowed");
        }
        Instant now = support.now();
        Instant expiresAt = now.plus(action.delegateFor());
        List<TaskAssignment> assignments = new ArrayList<>(task.assignments());
        TaskAssignment original = assignments.get(index);
        assignments.set(
                index,
                new TaskAssignment(
                        original.participant(),
                        AssignmentStatus.DELEGATED,
                        null,
                        original.assignedAt(),
                        null,
                        null));
        assignments.add(
                new TaskAssignment(
                        action.toAssignee(), AssignmentStatus.PENDING, null, now, null, null));
        HumanTask updated =
                TaskSupport.withAssignments(task, assignments, HumanTaskStatus.PENDING, now, null);
        state.tasks.put(updated.id(), updated);

        Delegation delegation =
                new Delegation(
                        task.id(),
                        action.fromAssignee(),
                        action.toAssignee().value(),
                        expiresAt,
                        action.actionId());
        state.delegations.add(delegation);
        timeouts.schedule(() -> timeouts.onDelegationExpired(state.id, delegation), expiresAt);

        support.event(
                state,
                "TASK_DELEGATED",
                updated.stepName(),
                updated.id(),
                action.actionId(),
                support.attrs(
                        "fromAssignee",
                        action.fromAssignee(),
                        "toAssignee",
                        action.toAssignee().value(),
                        "expiresAt",
                        expiresAt.toString(),
                        "reason",
                        support.nullSafe(action.reason())));
    }

    void onWithdraw(InstanceState state, WithdrawAction action) {
        support.requireCapability(support.capabilities().supportsWithdraw(), "withdraw");
        HumanTask task = support.requireTask(state, action.taskId());
        if (task.status() != HumanTaskStatus.PENDING) {
            throw new WorkflowStateConflictException(
                    "cannot withdraw a task in status " + task.status());
        }
        HumanTaskSpec spec = support.specOf(state, task);
        if (!spec.allowWithdraw()) {
            throw new WorkflowStateConflictException(
                    "withdraw is not allowed for step " + task.stepName());
        }
        boolean rejectedByOperator =
                task.assignments().stream()
                        .filter(a -> a.status() == AssignmentStatus.COMPLETED)
                        .map(TaskAssignment::record)
                        .anyMatch(
                                r ->
                                        r.assignee().equals(action.operator())
                                                && r.decision() == ActionDecision.REJECT);
        if (rejectedByOperator) {
            throw new WorkflowStateConflictException("cannot withdraw a REJECT record");
        }
        boolean approvedByOperator =
                task.assignments().stream()
                        .filter(a -> a.status() == AssignmentStatus.COMPLETED)
                        .map(TaskAssignment::record)
                        .anyMatch(
                                r ->
                                        r.assignee().equals(action.operator())
                                                && r.decision() == ActionDecision.APPROVE);
        if (!approvedByOperator) {
            throw new WithdrawNotAllowedException(
                    action.operator() + " has no APPROVE record on task " + task.id().value());
        }

        int targetIndex = support.stepIndex(state, action.targetStep());
        int currentIndex = support.stepIndex(state, task.stepName());
        if (targetIndex < 0 || targetIndex > currentIndex) {
            throw new WithdrawNotAllowedException(
                    "targetStep '"
                            + action.targetStep()
                            + "' is not upstream of "
                            + task.stepName());
        }
        if (support.hasCompletedStepAfter(state, currentIndex)) {
            throw new WithdrawNotAllowedException("downstream step has already completed");
        }

        Instant now = support.now();
        List<TaskAssignment> assignments =
                lifecycle.cancelActiveAssignments(state, task, "WITHDRAWN", now);
        HumanTask withdrawn =
                TaskSupport.withAssignments(task, assignments, HumanTaskStatus.WITHDRAWN, now, now);
        state.tasks.put(withdrawn.id(), withdrawn);
        support.event(
                state,
                "TASK_WITHDRAWN",
                withdrawn.stepName(),
                withdrawn.id(),
                action.actionId(),
                support.attrs(
                        "operator",
                        action.operator(),
                        "withdrawnRecordAssignee",
                        action.operator(),
                        "targetStep",
                        action.targetStep(),
                        "reason",
                        support.nullSafe(action.reason())));
        lifecycle.discardAfterPending(state, withdrawn.id(), "WITHDRAWN");
        engine.rollback(
                state, action.targetStep(), withdrawn.stepName(), action.actionId(), "WITHDRAWN");
    }

    void onWithdrawInstance(InstanceState state, WithdrawInstanceAction action) {
        support.requireCapability(
                support.capabilities().supportsWithdrawInstance(), "withdraw-instance");
        if (!state.startedBy.equals(action.operator())) {
            throw new WithdrawNotAllowedException("only startedBy can withdraw the whole instance");
        }
        Instant now = support.now();
        for (HumanTask task : new ArrayList<>(state.tasks.values())) {
            if (task.status() != HumanTaskStatus.PENDING) {
                continue;
            }
            List<TaskAssignment> assignments =
                    lifecycle.cancelActiveAssignments(state, task, "WITHDRAWN", now);
            HumanTask withdrawn =
                    TaskSupport.withAssignments(
                            task, assignments, HumanTaskStatus.WITHDRAWN, now, now);
            state.tasks.put(withdrawn.id(), withdrawn);
            support.event(
                    state,
                    "TASK_WITHDRAWN",
                    withdrawn.stepName(),
                    withdrawn.id(),
                    action.actionId(),
                    support.attrs(
                            "operator",
                            action.operator(),
                            "targetStep",
                            action.targetStep(),
                            "reason",
                            support.nullSafe(action.reason())));
            lifecycle.discardAfterPending(state, withdrawn.id(), "WITHDRAWN");
        }
        lifecycle.discardAllAfterPending(state, "WITHDRAWN");
        state.status = WorkflowStatus.CANCELLED;
        state.completedAt = now;
        support.event(
                state,
                "INSTANCE_WITHDRAWN",
                state.currentStep,
                null,
                action.actionId(),
                support.attrs(
                        "operator",
                        action.operator(),
                        "targetStep",
                        action.targetStep(),
                        "reason",
                        support.nullSafe(action.reason())));
        support.event(
                state,
                "INSTANCE_ROLLED_BACK",
                state.currentStep,
                null,
                action.actionId(),
                support.attrs("targetStep", action.targetStep(), "byAction", action.actionId()));
    }

    void onUrge(InstanceState state, UrgeAction action) {
        support.requireCapability(support.capabilities().supportsUrge(), "urge");
        HumanTask task = support.requireTask(state, action.taskId());
        if (task.status() != HumanTaskStatus.PENDING) {
            throw new HumanTaskStateConflictException(
                    "cannot urge a task in status " + task.status());
        }
        HumanTaskSpec spec = support.specOf(state, task);
        if (!spec.allowUrge()) {
            throw new WorkflowStateConflictException(
                    "urge is not allowed for step " + task.stepName());
        }
        boolean participant =
                task.assignments().stream()
                        .anyMatch(a -> a.participant().value().equals(action.operator()));
        if (!participant && !InMemoryWorkflowSupport.ADMIN.equals(action.operator())) {
            throw new AssigneeNotAllowedException(
                    action.operator() + " is not a participant of task " + task.id().value());
        }
        boolean suspended =
                task.assignments().stream().anyMatch(a -> a.status() == AssignmentStatus.SUSPENDED);
        support.event(
                state,
                "TASK_URGED",
                task.stepName(),
                task.id(),
                action.actionId(),
                support.attrs(
                        "operator",
                        action.operator(),
                        "message",
                        support.nullSafe(action.message()),
                        "targetStatus",
                        suspended ? "SUSPENDED" : "PENDING"));
    }

    void onCc(InstanceState state, CcAction action) {
        support.requireCapability(support.capabilities().supportsCc(), "cc");
        if (action.stepName() != null) {
            StepDefinition step = support.requireStep(state, action.stepName());
            if (step == null || step.kind() != StepKind.HUMAN_TASK || !step.humanTask().allowCc()) {
                throw new WorkflowStateConflictException(
                        "cc is not allowed for step " + action.stepName());
            }
        }
        support.event(
                state,
                "TASK_CC",
                action.stepName(),
                null,
                action.actionId(),
                support.attrs(
                        "operator",
                        action.operator(),
                        "recipients",
                        support.participantText(action.recipients()),
                        "message",
                        support.nullSafe(action.message())));
    }

    void onResume(InstanceState state, ResumeWorkflowAction action) {
        support.requireCapability(support.capabilities().supportsResumeWorkflow(), "resume");
        if (state.status != WorkflowStatus.WAITING) {
            throw new WorkflowStateConflictException(
                    "resume requires WAITING instance, current status is " + state.status);
        }
        if (!state.startedBy.equals(action.operator())
                && !InMemoryWorkflowSupport.ADMIN.equals(action.operator())) {
            throw new WorkflowStateConflictException(
                    "only startedBy or an administrator can resume the instance");
        }
        StepDefinition step = support.requireCurrentStep(state);
        if (step.kind() != StepKind.HUMAN_TASK) {
            throw new WorkflowStateConflictException(
                    "resume requires a HUMAN_TASK step, current is " + step.kind());
        }
        HumanTaskSpec spec = step.humanTask();
        Duration effectiveDueIn = action.newDueIn() != null ? action.newDueIn() : spec.dueIn();
        Instant startedAt = state.stepStartedAt.get(step.stepName());
        Duration timeout = timeouts.stepTimeout(state, step);
        if (timeout != null && effectiveDueIn != null && startedAt != null) {
            Duration elapsed = Duration.between(startedAt, support.now());
            if (elapsed.isNegative()) {
                elapsed = Duration.ZERO;
            }
            if (elapsed.plus(effectiveDueIn).compareTo(timeout) > 0) {
                throw new WorkflowStateConflictException(
                        "remaining step timeout budget is insufficient for newDueIn");
            }
        }
        Instant now = support.now();
        state.status = WorkflowStatus.RUNNING;
        support.event(
                state,
                "INSTANCE_RESUMED",
                step.stepName(),
                null,
                action.actionId(),
                support.attrs(
                        "operator",
                        action.operator(),
                        "newDueIn",
                        effectiveDueIn == null ? "" : effectiveDueIn.toString(),
                        "reason",
                        support.nullSafe(action.reason())));
        Instant dueAt = effectiveDueIn == null ? null : now.plus(effectiveDueIn);
        engine.createTask(
                state,
                step.stepName(),
                spec,
                HumanTaskKind.ORIGINAL,
                null,
                action.actionId(),
                dueAt);
    }

    void addCurrent(InstanceState state, HumanTask task, AddAssigneeAction action) {
        if (task.status() != HumanTaskStatus.PENDING) {
            throw new HumanTaskStateConflictException(
                    "cannot add assignee to a task in status " + task.status());
        }
        Instant now = support.now();
        List<ParticipantSpec> assignees =
                support.resolveAssignees(state, task.stepName(), action.newAssignees());
        List<TaskAssignment> extra =
                assignees.stream()
                        .map(
                                p ->
                                        new TaskAssignment(
                                                p, AssignmentStatus.PENDING, null, now, null, null))
                        .toList();
        HumanTask updated = TaskSupport.append(task, extra, now);
        state.tasks.put(updated.id(), updated);
        support.event(
                state,
                "TASK_ASSIGNEE_ADDED",
                task.stepName(),
                task.id(),
                action.actionId(),
                support.attrs(
                        "operator",
                        support.nullSafe(action.operator()),
                        "position",
                        AddAssigneePosition.CURRENT.name(),
                        "assignees",
                        support.participantText(assignees)));
    }

    void addBefore(
            InstanceState state, HumanTask task, HumanTaskSpec spec, AddAssigneeAction action) {
        if (task.status() != HumanTaskStatus.PENDING) {
            throw new HumanTaskStateConflictException(
                    "BEFORE add-assignee requires a PENDING task");
        }
        if (state.suspensions.containsKey(task.id())) {
            throw new WorkflowStateConflictException(
                    "task " + task.id().value() + " already has a BEFORE add-assignee in progress");
        }
        List<ParticipantSpec> assignees =
                support.resolveAssignees(state, task.stepName(), action.newAssignees());
        HumanTaskSpec derived =
                lifecycle.deriveSpec(
                        spec, assignees, lifecycle.beforeSignCompletion(spec, assignees));

        Instant now = support.now();
        Suspension suspension = new Suspension(now, task.dueAt());
        List<TaskAssignment> assignments = new ArrayList<>();
        for (TaskAssignment assignment : task.assignments()) {
            if (assignment.status() == AssignmentStatus.PENDING) {
                assignments.add(
                        new TaskAssignment(
                                assignment.participant(),
                                AssignmentStatus.SUSPENDED,
                                null,
                                assignment.assignedAt(),
                                now,
                                InMemoryWorkflowSupport.REASON_AFTER_ADDED_SUSPENDED));
                support.event(
                        state,
                        "TASK_ASSIGNMENT_SUSPENDED",
                        task.stepName(),
                        task.id(),
                        action.actionId(),
                        support.attrs(
                                "reason",
                                InMemoryWorkflowSupport.REASON_AFTER_ADDED_SUSPENDED,
                                "participant",
                                assignment.participant().value()));
            } else {
                assignments.add(assignment);
            }
        }
        HumanTask suspended =
                TaskSupport.withAssignments(task, assignments, HumanTaskStatus.PENDING, now, null);
        state.tasks.put(suspended.id(), suspended);
        state.suspensions.put(task.id(), suspension);

        Instant derivedDueAt =
                task.dueAt() == null
                        ? null
                        : now.plus(
                                Duration.between(now, task.dueAt()).isNegative()
                                        ? Duration.ZERO
                                        : Duration.between(now, task.dueAt()));
        HumanTask before =
                engine.createTask(
                        state,
                        suspended.stepName(),
                        derived,
                        HumanTaskKind.BEFORE_ADDED,
                        task.id(),
                        action.actionId(),
                        derivedDueAt);
        HumanTask original = support.withSuccessor(suspended, before.id(), now);
        state.tasks.put(original.id(), original);

        support.event(
                state,
                "TASK_ADD_ASSIGNEE_BEFORE",
                original.stepName(),
                original.id(),
                action.actionId(),
                support.attrs(
                        "operator",
                        support.nullSafe(action.operator()),
                        "spawnedTaskId",
                        before.id().value(),
                        "assignees",
                        support.participantText(assignees)));
    }

    void addAfter(
            InstanceState state, HumanTask task, HumanTaskSpec spec, AddAssigneeAction action) {
        if (task.status() != HumanTaskStatus.PENDING
                && task.status() != HumanTaskStatus.COMPLETED) {
            throw new HumanTaskStateConflictException(
                    "AFTER add-assignee requires a PENDING or COMPLETED task");
        }
        List<ParticipantSpec> assignees =
                support.resolveAssignees(state, task.stepName(), action.newAssignees());
        if (task.status() == HumanTaskStatus.PENDING) {
            state.afterPending.add(new AfterPending(task.id(), assignees, action.actionId()));
            return;
        }
        engine.spawnAfter(
                state, task, spec, new AfterPending(task.id(), assignees, action.actionId()));
    }
}
