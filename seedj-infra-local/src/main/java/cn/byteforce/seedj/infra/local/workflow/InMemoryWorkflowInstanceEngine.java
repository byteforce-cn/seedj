package cn.byteforce.seedj.infra.local.workflow;

import cn.byteforce.seedj.workflow.spi.ApproveRecord;
import cn.byteforce.seedj.workflow.spi.AssignmentStatus;
import cn.byteforce.seedj.workflow.spi.HumanTask;
import cn.byteforce.seedj.workflow.spi.HumanTaskKind;
import cn.byteforce.seedj.workflow.spi.HumanTaskSpec;
import cn.byteforce.seedj.workflow.spi.HumanTaskStatus;
import cn.byteforce.seedj.workflow.spi.ParticipantSpec;
import cn.byteforce.seedj.workflow.spi.StepDefinition;
import cn.byteforce.seedj.workflow.spi.StepKind;
import cn.byteforce.seedj.workflow.spi.TaskAssignment;
import cn.byteforce.seedj.workflow.spi.TaskId;
import cn.byteforce.seedj.workflow.spi.WorkflowFailure;
import cn.byteforce.seedj.workflow.spi.WorkflowStateConflictException;
import cn.byteforce.seedj.workflow.spi.WorkflowStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 拆分后的内存工作流协作者：InMemoryWorkflowInstanceEngine。 */
final class InMemoryWorkflowInstanceEngine {

    private final InMemoryWorkflowSupport support;
    private final InMemoryWorkflowDefinitionStore definitions;
    private final InMemoryWorkflowTaskLifecycle lifecycle;
    private InMemoryWorkflowTimeouts timeouts;
    InMemoryWorkflowInstanceEngine(InMemoryWorkflowSupport support, InMemoryWorkflowDefinitionStore definitions,
           InMemoryWorkflowTaskLifecycle lifecycle) {
        this.support = support;
        this.definitions = definitions;
        this.lifecycle = lifecycle;
    }

    void bindTimeouts(InMemoryWorkflowTimeouts timeouts) {
        this.timeouts = timeouts;
    }

    void evaluate(InstanceState state, HumanTask task) {
        if (task.status() != HumanTaskStatus.PENDING) {
            return;
        }
        HumanTaskSpec spec = support.specOf(state, task);
        if (TaskSupport.satisfied(task, spec.signCompletion())) {
            completeTaskSatisfied(state, task);
            return;
        }
        if (!TaskSupport.deadlocked(state, task)) {
            return;
        }
        List<ApproveRecord> rejects = TaskSupport.rejectRecords(task);
        if (!rejects.isEmpty()) {
            String target = state.lastRejectTarget.get(task.id());
            if (target == null) {
                target = spec.rejectTargets().stream().findFirst().orElse(null);
            }
            if (target == null) {
                failUnsatisfiable(state, task);
                return;
            }
            rejectTask(state, task, target, "SIGN_COMPLETION_UNSATISFIABLE", null);
            return;
        }
        failUnsatisfiable(state, task);
    }

    void completeTaskSatisfied(InstanceState state, HumanTask task) {
        Instant now = support.now();
        List<TaskAssignment> assignments = lifecycle.cancelActiveAssignments(state, task,
                InMemoryWorkflowSupport.REASON_RULE_SATISFIED, now);
        HumanTask completed = TaskSupport.withAssignments(task, assignments,
                HumanTaskStatus.COMPLETED, now, now);
        state.tasks.put(completed.id(), completed);
        state.suspensions.remove(completed.id());
        support.event(state, "TASK_COMPLETED", completed.stepName(), completed.id(), null,
                support.attrs("assignees", support.participantText(completed.pendingAssignees().isEmpty()
                                ? completed.assignments().stream()
                                .map(TaskAssignment::participant).toList()
                                : completed.pendingAssignees())));
        spawnAllAfterPending(state, completed);
        if (completed.kind() == HumanTaskKind.BEFORE_ADDED) {
            resumePredecessor(state, completed);
        }
        if (Objects.equals(state.currentStep, completed.stepName())
                && state.status != WorkflowStatus.COMPLETED) {
            maybeAdvance(state);
        }
    }

    void failUnsatisfiable(InstanceState state, HumanTask task) {
        Instant now = support.now();
        List<TaskAssignment> assignments = lifecycle.cancelActiveAssignments(state, task,
                "SIGN_COMPLETION_UNSATISFIABLE", now);
        HumanTask cancelled = TaskSupport.withAssignments(task, assignments,
                HumanTaskStatus.CANCELLED, now, now);
        state.tasks.put(cancelled.id(), cancelled);
        support.event(state, "TASK_CANCELLED", cancelled.stepName(), cancelled.id(), null,
                support.attrs("reason", "SIGN_COMPLETION_UNSATISFIABLE"));
        lifecycle.discardAfterPending(state, cancelled.id(), "UPSTREAM_ROLLBACK");
        HumanTaskSpec spec = support.specOf(state, cancelled);
        int required = lifecycle.requiredCount(spec.signCompletion(), TaskSupport.denominator(cancelled));
        failInstance(state, "SIGN_COMPLETION_UNSATISFIABLE",
                "approval rule cannot be satisfied for step " + cancelled.stepName(),
                cancelled.stepName(),
                Map.of("approveCount", Integer.toString(TaskSupport.approvals(cancelled)),
                        "requiredCount", Integer.toString(required)));
    }

    void rejectTask(InstanceState state, HumanTask task, String targetStep,
                            String reason, String actionId) {
        Instant now = support.now();
        List<TaskAssignment> assignments = lifecycle.cancelActiveAssignments(state, task, reason, now);
        HumanTask rejected = TaskSupport.withAssignments(task, assignments,
                HumanTaskStatus.REJECTED, now, now);
        state.tasks.put(rejected.id(), rejected);
        state.suspensions.remove(rejected.id());
        String rejectActionIds = TaskSupport.rejectRecords(rejected).stream()
                .map(r -> r.extra().getOrDefault("actionId", ""))
                .filter(s -> !s.isEmpty())
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        support.event(state, "TASK_REJECTED", rejected.stepName(), rejected.id(), actionId,
                support.attrs("reason", reason,
                        "rejectActionIds", rejectActionIds));
        lifecycle.discardAfterPending(state, rejected.id(), "REJECTED");
        rollback(state, targetStep, rejected.stepName(), actionId, reason);
    }

    void rollback(InstanceState state, String targetStep, String fromStep,
                          String actionId, String reason) {
        int targetIndex = support.stepIndex(state, targetStep);
        if (targetIndex < 0) {
            throw new WorkflowStateConflictException("unknown targetStep '" + targetStep + "'");
        }
        Instant now = support.now();
        for (HumanTask candidate : new ArrayList<>(state.tasks.values())) {
            if (candidate.status() != HumanTaskStatus.PENDING) {
                continue;
            }
            int index = support.stepIndex(state, candidate.stepName());
            if (index >= targetIndex) {
                lifecycle.cancelTask(state, candidate, InMemoryWorkflowSupport.REASON_UPSTREAM_ROLLBACK,
                        InMemoryWorkflowSupport.REASON_UPSTREAM_ROLLBACK, now);
            }
        }
        state.completedSteps.removeIf(step -> {
            int index = support.stepIndex(state, step);
            return index >= targetIndex;
        });
        state.afterPending.removeIf(p -> {
            HumanTask original = state.tasks.get(p.originalTaskId());
            return original == null || support.stepIndex(state, original.stepName()) >= targetIndex;
        });
        state.currentStep = targetStep;
        support.event(state, "INSTANCE_ROLLED_BACK", targetStep, null, actionId,
                support.attrs("targetStep", targetStep,
                        "fromStep", fromStep,
                        "reason", reason,
                        "byAction", actionId));
        enterStep(state, targetStep, actionId);
    }

    void failInstance(InstanceState state, String errorType, String message,
                              String failedStep, Map<String, String> details) {
        Instant now = support.now();
        for (HumanTask task : new ArrayList<>(state.tasks.values())) {
            if (task.status() == HumanTaskStatus.PENDING) {
                lifecycle.cancelTask(state, task, errorType, errorType, now);
            }
        }
        state.status = WorkflowStatus.FAILED;
        state.completedAt = now;
        state.failure = new WorkflowFailure(errorType, message, false, failedStep, details);
        support.event(state, "INSTANCE_FAILED", failedStep, null, null,
                support.attrs("errorType", errorType,
                        "message", support.nullSafe(message)));
        InMemoryWorkflowSupport.LOG.warn("workflow failed instanceId={} definitionId={} version={} step={} "
                        + "errorType={} message={}",
                state.id.value(), state.definition.id(),
                definitions.versionText(state.definition.version()), failedStep, errorType, message);
    }

    void enterStep(InstanceState state, String stepName, String actionId) {
        if (stepName == null) {
            completeInstance(state);
            return;
        }
        StepDefinition step = support.requireStep(state, stepName);
        if (step == null) {
            throw new WorkflowStateConflictException("unknown step '" + stepName + "'");
        }
        Instant now = support.now();
        state.currentStep = stepName;
        state.status = WorkflowStatus.RUNNING;
        state.stepStartedAt.put(stepName, now);
        Duration timeout = timeouts.stepTimeout(state, step);
        if (timeout != null) {
            state.stepDeadline.put(stepName, now.plus(timeout));
        }
        support.event(state, "STEP_STARTED", stepName, null, actionId,
                support.attrs("kind", step.kind().name()));
        timeouts.scheduleStepTimeout(state, step, now, timeout);

        if (step.kind() == StepKind.HUMAN_TASK) {
            createTask(state, stepName, step.humanTask(), HumanTaskKind.ORIGINAL,
                    null, actionId, null);
            return;
        }
        if (step.kind() == StepKind.WAIT_SIGNAL) {
            return;
        }
        // ACTIVITY / COMPENSATION：内存实现没有外部执行器契约，立即完成。
        Instant doneAt = support.now();
        state.completedSteps.add(stepName);
        support.event(state, "STEP_COMPLETED", stepName, null, actionId, Map.of());
        enterStep(state, support.nextStepName(state.definition, stepName), actionId);
        state.updatedAt = doneAt;
    }

    void maybeAdvance(InstanceState state) {
        if (state.terminal()) {
            return;
        }
        String stepName = state.currentStep;
        if (stepName == null) {
            return;
        }
        StepDefinition step = support.requireStep(state, stepName);
        if (step == null || step.kind() == StepKind.WAIT_SIGNAL) {
            return;
        }
        boolean pendingTask = state.tasks.values().stream()
                .anyMatch(t -> t.status() == HumanTaskStatus.PENDING
                        && t.stepName().equals(stepName));
        if (pendingTask) {
            return;
        }
        boolean pendingAfter = state.afterPending.stream().anyMatch(p -> {
            HumanTask original = state.tasks.get(p.originalTaskId());
            return original != null && original.stepName().equals(stepName);
        });
        if (pendingAfter) {
            return;
        }
        if (!state.completedSteps.contains(stepName)) {
            state.completedSteps.add(stepName);
            support.event(state, "STEP_COMPLETED", stepName, null, null, Map.of());
        }
        enterStep(state, support.nextStepName(state.definition, stepName), null);
    }

    void completeStepAndAdvance(InstanceState state, String stepName, String actionId) {
        state.completedSteps.add(stepName);
        support.event(state, "STEP_COMPLETED", stepName, null, actionId, Map.of());
        enterStep(state, support.nextStepName(state.definition, stepName), actionId);
    }

    void completeInstance(InstanceState state) {
        state.currentStep = null;
        state.status = WorkflowStatus.COMPLETED;
        state.completedAt = support.now();
        support.event(state, "INSTANCE_COMPLETED", null, null, null, Map.of());
        InMemoryWorkflowSupport.LOG.info("workflow completed instanceId={} definitionId={} version={}",
                state.id.value(), state.definition.id(),
                definitions.versionText(state.definition.version()));
    }

    HumanTask createTask(InstanceState state, String stepName, HumanTaskSpec spec,
                                 HumanTaskKind kind, TaskId predecessor, String actionId,
                                 Instant dueAtOverride) {
        Instant now = support.now();
        List<ParticipantSpec> assignees = support.resolveAssignees(state, stepName, spec.assignees());
        List<TaskAssignment> assignments = assignees.stream()
                .map(p -> new TaskAssignment(p, AssignmentStatus.PENDING, null, now, null, null))
                .toList();
        Instant dueAt = dueAtOverride != null ? dueAtOverride
                : (spec.dueIn() == null ? null : now.plus(spec.dueIn()));
        HumanTask task = new HumanTask(new TaskId("task-" + support.idGenerator.id()),
                state.id, stepName, kind, HumanTaskStatus.PENDING, assignments, Map.of(),
                predecessor, null, actionId, now, now, null, dueAt, Map.of());
        state.tasks.put(task.id(), task);
        state.taskSpecs.put(task.id(), spec);
        support.event(state, "TASK_CREATED", stepName, task.id(), actionId,
                support.attrs("kind", kind.name(),
                        "assignees", support.participantText(assignees),
                        "dueAt", dueAt == null ? "" : dueAt.toString()));
        timeouts.scheduleTaskDue(state, task);
        return task;
    }

    void resumePredecessor(InstanceState state, HumanTask beforeTask) {
        TaskId predecessorId = beforeTask.predecessorTaskId();
        if (predecessorId == null) {
            return;
        }
        HumanTask original = state.tasks.get(predecessorId);
        if (original == null || original.status() != HumanTaskStatus.PENDING) {
            return;
        }
        Suspension suspension = state.suspensions.remove(original.id());
        Instant now = support.now();
        List<TaskAssignment> assignments = new ArrayList<>();
        List<String> resumed = new ArrayList<>();
        for (TaskAssignment assignment : original.assignments()) {
            if (assignment.status() == AssignmentStatus.SUSPENDED) {
                assignments.add(new TaskAssignment(assignment.participant(),
                        AssignmentStatus.PENDING, null, assignment.assignedAt(), null, null));
                resumed.add(assignment.participant().value());
            } else {
                assignments.add(assignment);
            }
        }
        Instant newDueAt = original.dueAt();
        if (suspension != null && suspension.originalDueAt() != null) {
            Duration suspendedFor = Duration.between(suspension.suspendedAt(), now);
            if (suspendedFor.isNegative()) {
                suspendedFor = Duration.ZERO;
            }
            newDueAt = suspension.originalDueAt().plus(suspendedFor);
        }
        HumanTask updated = TaskSupport.withAssignments(original, assignments,
                HumanTaskStatus.PENDING, now, null);
        updated = support.withDueAt(updated, newDueAt, now);
        state.tasks.put(updated.id(), updated);
        for (String participant : resumed) {
            support.event(state, "TASK_ASSIGNMENT_RESUMED", updated.stepName(), updated.id(), null,
                    support.attrs("participant", participant,
                            "originalDueAt", original.dueAt() == null ? ""
                                    : original.dueAt().toString(),
                            "newDueAt", newDueAt == null ? "" : newDueAt.toString()));
        }
        timeouts.scheduleTaskDue(state, updated);
        evaluate(state, updated);
    }

    void spawnAllAfterPending(InstanceState state, HumanTask original) {
        List<AfterPending> pending = state.afterPending.stream()
                .filter(p -> p.originalTaskId().equals(original.id()))
                .toList();
        state.afterPending.removeAll(pending);
        HumanTaskSpec spec = support.specOf(state, original);
        for (AfterPending after : pending) {
            spawnAfter(state, original, spec, after);
        }
    }

    void spawnAfter(InstanceState state, HumanTask original, HumanTaskSpec spec,
                            AfterPending after) {
        Instant now = support.now();
        HumanTaskSpec derived = lifecycle.deriveSpec(spec, after.assignees(),
                lifecycle.afterSignCompletion(spec, after.assignees()));
        HumanTask spawned = createTask(state, original.stepName(), derived,
                HumanTaskKind.AFTER_ADDED, original.id(), after.actionId(), null);
        HumanTask updatedOriginal = support.withSuccessor(original, spawned.id(), now);
        state.tasks.put(updatedOriginal.id(), updatedOriginal);
        if (state.currentStep != null) {
            int currentIndex = support.stepIndex(state, state.currentStep);
            int originalIndex = support.stepIndex(state, original.stepName());
            if (currentIndex <= originalIndex) {
                state.currentStep = original.stepName();
            }
        }
        support.event(state, "TASK_ADD_ASSIGNEE_AFTER", original.stepName(), updatedOriginal.id(),
                after.actionId(), support.attrs("spawnedTaskId", spawned.id().value(),
                        "assignees", support.participantText(after.assignees())));
    }

}
