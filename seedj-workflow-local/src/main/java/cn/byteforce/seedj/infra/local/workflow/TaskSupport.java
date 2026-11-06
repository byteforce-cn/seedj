package cn.byteforce.seedj.infra.local.workflow;

import cn.byteforce.seedj.workflow.spi.AllApprove;
import cn.byteforce.seedj.workflow.spi.AnyApprove;
import cn.byteforce.seedj.workflow.spi.ApproveRecord;
import cn.byteforce.seedj.workflow.spi.AssignmentStatus;
import cn.byteforce.seedj.workflow.spi.AtLeastCount;
import cn.byteforce.seedj.workflow.spi.AtLeastPercentage;
import cn.byteforce.seedj.workflow.spi.HumanTask;
import cn.byteforce.seedj.workflow.spi.HumanTaskStatus;
import cn.byteforce.seedj.workflow.spi.SignCompletion;
import cn.byteforce.seedj.workflow.spi.TaskAssignment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 会签计数与任务记录更新的纯函数。 */
final class TaskSupport {

    private TaskSupport() {
    }

    static boolean active(AssignmentStatus status) {
        return status == AssignmentStatus.PENDING
                || status == AssignmentStatus.SUSPENDED
                || status == AssignmentStatus.DELEGATED;
    }

    static int denominator(HumanTask task) {
        return (int) task.assignments().stream()
                .filter(a -> a.status() == AssignmentStatus.PENDING
                        || a.status() == AssignmentStatus.SUSPENDED
                        || a.status() == AssignmentStatus.COMPLETED)
                .count();
    }

    static int approvals(HumanTask task) {
        return (int) task.assignments().stream()
                .filter(a -> a.status() == AssignmentStatus.COMPLETED)
                .map(TaskAssignment::record)
                .filter(r -> r.decision() == cn.byteforce.seedj.workflow.spi.ActionDecision.APPROVE)
                .count();
    }

    static int rejects(HumanTask task) {
        return (int) task.assignments().stream()
                .filter(a -> a.status() == AssignmentStatus.COMPLETED)
                .map(TaskAssignment::record)
                .filter(r -> r.decision() == cn.byteforce.seedj.workflow.spi.ActionDecision.REJECT)
                .count();
    }

    static List<ApproveRecord> rejectRecords(HumanTask task) {
        return task.assignments().stream()
                .filter(a -> a.status() == AssignmentStatus.COMPLETED)
                .map(TaskAssignment::record)
                .filter(r -> r.decision() == cn.byteforce.seedj.workflow.spi.ActionDecision.REJECT)
                .toList();
    }

    static boolean satisfied(HumanTask task, SignCompletion rule) {
        int denominator = denominator(task);
        int approvals = approvals(task);
        if (rule instanceof AllApprove) {
            return denominator > 0 && approvals == denominator && rejects(task) == 0;
        }
        if (rule instanceof AnyApprove) {
            return approvals >= 1;
        }
        if (rule instanceof AtLeastCount c) {
            return approvals >= c.count();
        }
        if (rule instanceof AtLeastPercentage p) {
            return approvals >= (int) Math.ceil(p.percentage() * denominator);
        }
        throw new IllegalStateException("unknown sign completion: " + rule);
    }

    static boolean hasActiveAssignment(HumanTask task) {
        // DELEGATED 的活跃性由代理人 PENDING assignment 表达；代理人已处理时
        // 原 DELEGATED 是历史记录，不参与死锁判定（§1.5.4 / §1.5.12）。
        return task.assignments().stream().anyMatch(a ->
                a.status() == AssignmentStatus.PENDING
                        || a.status() == AssignmentStatus.SUSPENDED);
    }

    /** §1.5.12：无 PENDING/SUSPENDED/DELEGATED，且无待派生 AFTER 记录。 */
    static boolean deadlocked(InstanceState state, HumanTask task) {
        if (hasActiveAssignment(task)) {
            return false;
        }
        return state.afterPending.stream()
                .noneMatch(p -> p.originalTaskId().equals(task.id()));
    }

    static HumanTask withAssignments(HumanTask task, List<TaskAssignment> assignments,
                                     HumanTaskStatus status, Instant now, Instant completedAt) {
        return new HumanTask(task.id(), task.instanceId(), task.stepName(), task.kind(), status,
                assignments, task.formData(), task.predecessorTaskId(), task.successorTaskId(),
                task.spawnedByActionId(), task.createdAt(), now, completedAt, task.dueAt(),
                task.metadata());
    }

    static HumanTask replace(HumanTask task, int index, TaskAssignment assignment,
                             Instant now) {
        List<TaskAssignment> copy = new ArrayList<>(task.assignments());
        copy.set(index, assignment);
        return withAssignments(task, copy, task.status(), now, task.completedAt());
    }

    static HumanTask append(HumanTask task, List<TaskAssignment> extra, Instant now) {
        List<TaskAssignment> copy = new ArrayList<>(task.assignments());
        copy.addAll(extra);
        return withAssignments(task, copy, task.status(), now, task.completedAt());
    }

    static TaskAssignment cancelled(TaskAssignment assignment, String reason, Instant now) {
        return new TaskAssignment(assignment.participant(), AssignmentStatus.CANCELLED,
                null, assignment.assignedAt(), now, reason);
    }

    static TaskAssignment completed(TaskAssignment assignment, ApproveRecord record, Instant now) {
        return new TaskAssignment(assignment.participant(), AssignmentStatus.COMPLETED,
                record, assignment.assignedAt(), now, null);
    }
}
