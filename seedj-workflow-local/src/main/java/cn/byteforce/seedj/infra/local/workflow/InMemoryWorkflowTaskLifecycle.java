package cn.byteforce.seedj.infra.local.workflow;

import cn.byteforce.seedj.workflow.spi.AllApprove;
import cn.byteforce.seedj.workflow.spi.AnyApprove;
import cn.byteforce.seedj.workflow.spi.AssignmentStatus;
import cn.byteforce.seedj.workflow.spi.AtLeastCount;
import cn.byteforce.seedj.workflow.spi.AtLeastPercentage;
import cn.byteforce.seedj.workflow.spi.AtLeastRejects;
import cn.byteforce.seedj.workflow.spi.HumanTask;
import cn.byteforce.seedj.workflow.spi.HumanTaskSpec;
import cn.byteforce.seedj.workflow.spi.HumanTaskStatus;
import cn.byteforce.seedj.workflow.spi.ParticipantSpec;
import cn.byteforce.seedj.workflow.spi.RejectCompletion;
import cn.byteforce.seedj.workflow.spi.SignCompletion;
import cn.byteforce.seedj.workflow.spi.TaskAssignment;
import cn.byteforce.seedj.workflow.spi.TaskId;
import cn.byteforce.seedj.workflow.spi.WorkflowStateConflictException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 拆分后的内存工作流协作者：InMemoryWorkflowTaskLifecycle。 */
final class InMemoryWorkflowTaskLifecycle {

    private final InMemoryWorkflowSupport support;
    InMemoryWorkflowTaskLifecycle(InMemoryWorkflowSupport support) {
        this.support = support;
    }

    List<TaskAssignment> cancelActiveAssignments(InstanceState state, HumanTask task,
                                                         String reason, Instant now) {
        List<TaskAssignment> assignments = new ArrayList<>();
        for (TaskAssignment assignment : task.assignments()) {
            if (TaskSupport.active(assignment.status())) {
                assignments.add(TaskSupport.cancelled(assignment, reason, now));
                support.event(state, "TASK_ASSIGNMENT_AUTO_CANCELLED", task.stepName(), task.id(),
                        null, support.attrs("reason", reason,
                                "participant", assignment.participant().value()));
                if (assignment.status() == AssignmentStatus.DELEGATED) {
                    cancelDelegationsFor(state, task.id());
                } else {
                    cancelDelegationForProxy(state, task.id(),
                            assignment.participant().value());
                }
            } else {
                assignments.add(assignment);
            }
        }
        return assignments;
    }

    void cancelTask(InstanceState state, HumanTask task, String assignmentReason,
                            String eventReason, Instant now) {
        List<TaskAssignment> assignments = cancelActiveAssignments(state, task,
                assignmentReason, now);
        HumanTask cancelled = TaskSupport.withAssignments(task, assignments,
                HumanTaskStatus.CANCELLED, now, now);
        state.tasks.put(cancelled.id(), cancelled);
        state.suspensions.remove(cancelled.id());
        support.event(state, "TASK_CANCELLED", cancelled.stepName(), cancelled.id(), null,
                support.attrs("reason", eventReason));
        discardAfterPending(state, cancelled.id(), InMemoryWorkflowSupport.REASON_UPSTREAM_ROLLBACK);
    }

    void cancelAllActiveTasks(InstanceState state, String reason) {
        Instant now = support.now();
        for (HumanTask task : new ArrayList<>(state.tasks.values())) {
            if (task.status() == HumanTaskStatus.PENDING) {
                cancelTask(state, task, reason, reason, now);
            }
        }
    }

    void cancelDelegationsFor(InstanceState state, TaskId taskId) {
        for (Delegation delegation : state.delegations) {
            if (delegation.taskId.equals(taskId) && !delegation.handled) {
                delegation.handled = true;
                delegation.cancelled = true;
            }
        }
    }

    void cancelDelegationForProxy(InstanceState state, TaskId taskId, String proxy) {
        for (Delegation delegation : state.delegations) {
            if (delegation.taskId.equals(taskId) && !delegation.handled
                    && delegation.proxyParticipant.equals(proxy)) {
                delegation.handled = true;
                delegation.cancelled = true;
            }
        }
    }

    void discardAfterPending(InstanceState state, TaskId taskId, String reason) {
        List<AfterPending> matches = state.afterPending.stream()
                .filter(p -> p.originalTaskId().equals(taskId))
                .toList();
        if (matches.isEmpty()) {
            return;
        }
        state.afterPending.removeAll(matches);
        for (AfterPending pending : matches) {
            support.event(state, "TASK_AFTER_SPAWN_DISCARDED", null, taskId, pending.actionId(),
                    support.attrs("reason", reason));
        }
    }

    void discardAllAfterPending(InstanceState state, String reason) {
        List<AfterPending> all = List.copyOf(state.afterPending);
        state.afterPending.clear();
        for (AfterPending pending : all) {
            support.event(state, "TASK_AFTER_SPAWN_DISCARDED", null, pending.originalTaskId(),
                    pending.actionId(), support.attrs("reason", reason));
        }
    }

    HumanTaskSpec deriveSpec(HumanTaskSpec base, List<ParticipantSpec> assignees,
                                     SignCompletion signCompletion) {
        RejectCompletion rejectCompletion = clampRejects(base.rejectCompletion(),
                assignees.size());
        return new HumanTaskSpec(assignees, signCompletion, rejectCompletion,
                base.rejectTargets(), base.formKey(), base.formSchema(),
                base.allowAddAssignee(), base.allowedAddPositions(),
                base.allowRemoveAssignee(), base.allowTransfer(), base.allowDelegate(),
                base.allowWithdraw(), base.allowUrge(), base.allowCc(),
                base.dueIn(), base.metadata());
    }

    SignCompletion beforeSignCompletion(HumanTaskSpec spec,
                                                List<ParticipantSpec> assignees) {
        int newSize = assignees.size();
        if (spec.signCompletion() instanceof AtLeastCount count) {
            boolean reduce = !"false".equalsIgnoreCase(
                    spec.metadata().get("allowAddAssigneeReducesThreshold"));
            if (count.count() > newSize && !reduce) {
                throw new WorkflowStateConflictException(
                        "AtLeastCount(" + count.count() + ") exceeds BEFORE assignee size "
                                + newSize + " and threshold reduction is disabled");
            }
            return new AtLeastCount(Math.min(count.count(), newSize));
        }
        return spec.signCompletion();
    }

    SignCompletion afterSignCompletion(HumanTaskSpec spec,
                                               List<ParticipantSpec> assignees) {
        int newSize = assignees.size();
        String override = spec.metadata().get("afterAddSignCompletion");
        if (override == null || override.isBlank()) {
            return new AnyApprove();
        }
        String value = override.strip();
        if ("AllApprove".equalsIgnoreCase(value)) {
            return new AllApprove();
        }
        if (value.regionMatches(true, 0, "AtLeastCount:", 0, "AtLeastCount:".length())) {
            int count = Integer.parseInt(value.substring("AtLeastCount:".length()).strip());
            return new AtLeastCount(Math.min(Math.max(1, count), newSize));
        }
        if (value.regionMatches(true, 0, "AtLeastPercentage:", 0,
                "AtLeastPercentage:".length())) {
            double percentage = Double.parseDouble(
                    value.substring("AtLeastPercentage:".length()).strip());
            return new AtLeastPercentage(percentage);
        }
        return new AnyApprove();
    }

    static RejectCompletion clampRejects(RejectCompletion completion, int size) {
        if (completion instanceof AtLeastRejects rejects) {
            return new AtLeastRejects(Math.min(rejects.count(), size));
        }
        return completion;
    }

    static int requiredCount(SignCompletion rule, int denominator) {
        if (rule instanceof AllApprove) {
            return denominator;
        }
        if (rule instanceof AnyApprove) {
            return 1;
        }
        if (rule instanceof AtLeastCount count) {
            return count.count();
        }
        if (rule instanceof AtLeastPercentage percentage) {
            return (int) Math.ceil(percentage.percentage() * denominator);
        }
        return denominator;
    }

}
