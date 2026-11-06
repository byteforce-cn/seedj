package cn.byteforce.seedj.infra.local.workflow;

import cn.byteforce.seedj.workflow.spi.AssignmentStatus;
import cn.byteforce.seedj.workflow.spi.HumanTask;
import cn.byteforce.seedj.workflow.spi.HumanTaskSpec;
import cn.byteforce.seedj.workflow.spi.HumanTaskStatus;
import cn.byteforce.seedj.workflow.spi.StepDefinition;
import cn.byteforce.seedj.workflow.spi.TaskAssignment;
import cn.byteforce.seedj.workflow.spi.TaskId;
import cn.byteforce.seedj.workflow.spi.WorkflowInstanceId;
import cn.byteforce.seedj.workflow.spi.WorkflowStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** 拆分后的内存工作流协作者：InMemoryWorkflowTimeouts。 */
final class InMemoryWorkflowTimeouts {

    private final InMemoryWorkflowSupport support;
    private final InMemoryWorkflowInstanceEngine engine;
    private final InMemoryWorkflowTaskLifecycle lifecycle;
    InMemoryWorkflowTimeouts(InMemoryWorkflowSupport support, InMemoryWorkflowInstanceEngine engine,
             InMemoryWorkflowTaskLifecycle lifecycle) {
        this.support = support;
        this.engine = engine;
        this.lifecycle = lifecycle;
    }

    void onTaskDue(WorkflowInstanceId instanceId, TaskId taskId, Instant firedDueAt) {
        InstanceState state = support.instances.get(instanceId);
        if (state == null) {
            return;
        }
        state.lock.lock();
        try {
            if (state.terminal()) {
                return;
            }
            HumanTask task = state.tasks.get(taskId);
            if (task == null || task.status() != HumanTaskStatus.PENDING) {
                return;
            }
            if (!Objects.equals(task.dueAt(), firedDueAt)) {
                return;
            }
            if (task.assignments().stream().noneMatch(
                    a -> a.status() == AssignmentStatus.PENDING)) {
                return;
            }
            handleDueTimeout(state, task);
            support.touch(state);
        } finally {
            state.lock.unlock();
        }
    }

    void handleDueTimeout(InstanceState state, HumanTask task) {
        Instant now = support.now();
        List<TaskAssignment> assignments = lifecycle.cancelActiveAssignments(state, task,
                "TASK_OVERDUE", now);
        HumanTask cancelled = TaskSupport.withAssignments(task, assignments,
                HumanTaskStatus.CANCELLED, now, now);
        state.tasks.put(cancelled.id(), cancelled);
        state.suspensions.remove(cancelled.id());
        support.event(state, "TASK_OVERDUE", cancelled.stepName(), cancelled.id(), null,
                support.attrs("reason", "TASK_OVERDUE"));
        lifecycle.discardAfterPending(state, cancelled.id(), "TASK_OVERDUE");
        HumanTaskSpec spec = support.specOf(state, cancelled);
        String policy = spec.metadata().getOrDefault("timeoutPolicy", "FAILED");
        if ("WAITING".equalsIgnoreCase(policy)) {
            state.status = WorkflowStatus.WAITING;
            state.failure = null;
            support.event(state, "INSTANCE_WAITING", cancelled.stepName(), cancelled.id(), null,
                    support.attrs("reason", "TASK_OVERDUE"));
        } else {
            engine.failInstance(state, "TASK_OVERDUE",
                    "human task overdue at step " + cancelled.stepName(),
                    cancelled.stepName(),
                    Map.of("taskId", cancelled.id().value()));
        }
    }

    void onStepTimeout(InstanceState state, String stepName, Instant startedAt) {
        if (state.terminal()) {
            return;
        }
        if (!Objects.equals(state.currentStep, stepName)) {
            return;
        }
        if (!Objects.equals(state.stepStartedAt.get(stepName), startedAt)) {
            return;
        }
        Instant now = support.now();
        if (state.status == WorkflowStatus.WAITING) {
            engine.failInstance(state, "STEP_TIMEOUT",
                    "step timeout reached while instance was WAITING", stepName,
                    Map.of("step", stepName));
            support.touch(state);
            return;
        }
        for (HumanTask task : new ArrayList<>(state.tasks.values())) {
            if (task.status() == HumanTaskStatus.PENDING
                    && task.stepName().equals(stepName)) {
                lifecycle.cancelTask(state, task, "STEP_TIMEOUT", "STEP_TIMEOUT", now);
            }
        }
        engine.failInstance(state, "STEP_TIMEOUT",
                "step timeout reached for " + stepName, stepName,
                Map.of("step", stepName));
        support.touch(state);
    }

    void onWorkflowTimeout(InstanceState state) {
        if (state.terminal()) {
            return;
        }
        Instant now = support.now();
        lifecycle.cancelAllActiveTasks(state, "WORKFLOW_TIMEOUT");
        lifecycle.discardAllAfterPending(state, "UPSTREAM_ROLLBACK");
        engine.failInstance(state, "WORKFLOW_TIMEOUT",
                "workflow timeout reached", state.currentStep,
                Map.of("definitionId", state.definition.id().name()));
        support.touch(state);
    }

    void onDelegationExpired(WorkflowInstanceId instanceId, Delegation delegation) {
        InstanceState state = support.instances.get(instanceId);
        if (state == null) {
            return;
        }
        state.lock.lock();
        try {
            if (delegation.handled || delegation.cancelled || state.terminal()) {
                return;
            }
            HumanTask task = state.tasks.get(delegation.taskId);
            if (task == null || task.status() != HumanTaskStatus.PENDING) {
                delegation.handled = true;
                return;
            }
            int originalIndex = support.assignmentIndex(task, AssignmentStatus.DELEGATED,
                    delegation.originalParticipant);
            int proxyIndex = support.pendingIndex(task, delegation.proxyParticipant);
            Instant now = support.now();
            if (originalIndex < 0) {
                delegation.handled = true;
                return;
            }
            if (proxyIndex >= 0) {
                List<TaskAssignment> assignments = new ArrayList<>(task.assignments());
                TaskAssignment proxy = assignments.get(proxyIndex);
                assignments.set(proxyIndex, new TaskAssignment(proxy.participant(),
                        AssignmentStatus.CANCELLED, null, proxy.assignedAt(), now,
                        "DELEGATION_EXPIRED"));
                support.event(state, "TASK_ASSIGNMENT_AUTO_CANCELLED", task.stepName(), task.id(),
                        delegation.actionId,
                        support.attrs("reason", "DELEGATION_EXPIRED",
                                "participant", delegation.proxyParticipant));
                TaskAssignment original = assignments.get(originalIndex);
                assignments.set(originalIndex, new TaskAssignment(original.participant(),
                        AssignmentStatus.PENDING, null, original.assignedAt(), null, null));
                HumanTask updated = TaskSupport.withAssignments(task, assignments,
                        HumanTaskStatus.PENDING, now, null);
                state.tasks.put(updated.id(), updated);
                delegation.handled = true;
                support.event(state, "DELEGATION_EXPIRED", task.stepName(), task.id(),
                        delegation.actionId,
                        support.attrs("participant", delegation.originalParticipant,
                                "proxy", delegation.proxyParticipant));
                scheduleTaskDue(state, updated);
                engine.evaluate(state, updated);
            } else {
                boolean proxyCompleted = task.assignments().stream()
                        .anyMatch(a -> a.status() == AssignmentStatus.COMPLETED
                                && a.participant().value().equals(delegation.proxyParticipant)
                                && delegation.originalParticipant.equals(
                                        a.record() == null ? null : a.record().onBehalfOf()));
                delegation.handled = true;
                if (proxyCompleted) {
                    support.event(state, "DELEGATION_EXPIRED_NOOP", task.stepName(), task.id(),
                            delegation.actionId,
                            support.attrs("participant", delegation.originalParticipant,
                                    "proxy", delegation.proxyParticipant));
                }
            }
            support.touch(state);
        } finally {
            state.lock.unlock();
        }
    }

    void schedule(Runnable runnable, Instant at) {
        if (at == null || support.closed.get()) {
            return;
        }
        long delayMillis = Math.max(0,
                Duration.between(support.clock.instant(), at).toMillis());
        try {
            support.scheduler.schedule(runnable, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Provider 已关闭；状态已持久化，由调用方决定是否重启。
        }
    }

    void scheduleTaskDue(InstanceState state, HumanTask task) {
        if (task.dueAt() == null) {
            return;
        }
        Instant dueAt = task.dueAt();
        schedule(() -> onTaskDue(state.id, task.id(), dueAt), dueAt);
    }

    void scheduleStepTimeout(InstanceState state, StepDefinition step,
                                     Instant startedAt, Duration timeout) {
        if (timeout == null) {
            return;
        }
        Instant deadline = startedAt.plus(timeout);
        schedule(() -> onStepTimeout(state, step.stepName(), startedAt), deadline);
    }

    Duration stepTimeout(InstanceState state, StepDefinition step) {
        return step.timeout() != null ? step.timeout() : state.definition.defaultStepTimeout();
    }

    void scheduleWorkflowTimeout(InstanceState state) {
        Duration timeout = state.definition.workflowTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return;
        }
        schedule(() -> {
            state.lock.lock();
            try {
                onWorkflowTimeout(state);
            } finally {
                state.lock.unlock();
            }
        }, state.startedAt.plus(timeout));
    }

}
