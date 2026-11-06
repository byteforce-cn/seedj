package cn.byteforce.seedj.infra.local.workflow;

import cn.byteforce.seedj.workflow.spi.AssignmentStatus;
import cn.byteforce.seedj.workflow.spi.HumanTask;
import cn.byteforce.seedj.workflow.spi.HumanTaskStatus;
import cn.byteforce.seedj.workflow.spi.TaskId;
import cn.byteforce.seedj.workflow.spi.TaskQuery;
import cn.byteforce.seedj.workflow.spi.WorkflowHistoryEvent;
import cn.byteforce.seedj.workflow.spi.WorkflowInstance;
import cn.byteforce.seedj.workflow.spi.WorkflowInstanceId;
import cn.byteforce.seedj.workflow.spi.WorkflowSearchQuery;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 拆分后的内存工作流协作者：InMemoryWorkflowQueryService。 */
final class InMemoryWorkflowQueryService {

    private final InMemoryWorkflowSupport support;
    InMemoryWorkflowQueryService(InMemoryWorkflowSupport support) {
        this.support = support;
    }

    public Optional<WorkflowInstance> describe(WorkflowInstanceId instanceId) {
        InstanceState state = support.instances.get(instanceId);
        if (state == null) {
            return Optional.empty();
        }
        state.lock.lock();
        try {
            return Optional.of(support.snapshot(state));
        } finally {
            state.lock.unlock();
        }
    }

    public List<WorkflowInstance> search(WorkflowSearchQuery query) {
        Objects.requireNonNull(query, "query cannot be null");
        List<WorkflowInstance> matches = new ArrayList<>();
        for (InstanceState state : support.instances.values()) {
            state.lock.lock();
            try {
                WorkflowInstance snapshot = support.snapshot(state);
                if (query.definitionId() != null
                        && !snapshot.definitionId().equals(query.definitionId())) {
                    continue;
                }
                if (query.status() != null && snapshot.status() != query.status()) {
                    continue;
                }
                if (query.startedAfter() != null
                        && snapshot.startedAt().isBefore(query.startedAfter())) {
                    continue;
                }
                if (query.startedBefore() != null
                        && !snapshot.startedAt().isBefore(query.startedBefore())) {
                    continue;
                }
                matches.add(snapshot);
            } finally {
                state.lock.unlock();
            }
        }
        matches.sort(Comparator.comparing(WorkflowInstance::startedAt).reversed()
                .thenComparing(i -> i.id().value()));
        int offset = support.cursorOffset(query.cursor());
        if (offset >= matches.size()) {
            return List.of();
        }
        int end = Math.min(matches.size(), offset + query.limit());
        return List.copyOf(matches.subList(offset, end));
    }

    public List<WorkflowHistoryEvent> history(WorkflowInstanceId instanceId) {
        InstanceState state = support.requireInstance(instanceId);
        state.lock.lock();
        try {
            return List.copyOf(state.events);
        } finally {
            state.lock.unlock();
        }
    }

    public List<HumanTask> listTasks(TaskQuery query) {
        Objects.requireNonNull(query, "query cannot be null");
        List<HumanTask> matches = new ArrayList<>();
        for (InstanceState state : support.instances.values()) {
            state.lock.lock();
            try {
                for (HumanTask task : state.tasks.values()) {
                    if (query.instanceId() != null && !task.instanceId().equals(query.instanceId())) {
                        continue;
                    }
                    if (query.stepName() != null && !task.stepName().equals(query.stepName())) {
                        continue;
                    }
                    if (query.status() != null && task.status() != query.status()) {
                        continue;
                    }
                    if (query.status() == HumanTaskStatus.PENDING
                            && task.assignments().stream().noneMatch(
                                    a -> a.status() == AssignmentStatus.PENDING)) {
                        continue;
                    }
                    if (query.dueBefore() != null
                            && (task.dueAt() == null || task.dueAt().isAfter(query.dueBefore()))) {
                        continue;
                    }
                    if (query.pendingFor() != null
                            && !support.hasPendingAssignment(task, query.pendingFor())) {
                        continue;
                    }
                    if (query.completedBy() != null
                            && !support.hasCompletedBy(task, query.completedBy())) {
                        continue;
                    }
                    if (!query.candidateGroups().isEmpty()
                            && !support.hasCandidateGroup(task, query.candidateGroups())) {
                        continue;
                    }
                    matches.add(task);
                }
            } finally {
                state.lock.unlock();
            }
        }
        matches.sort(Comparator.comparing(HumanTask::createdAt)
                .thenComparing(t -> t.id().value()));
        int offset = support.cursorOffset(query.cursor());
        if (offset >= matches.size()) {
            return List.of();
        }
        int end = Math.min(matches.size(), offset + query.limit());
        return List.copyOf(matches.subList(offset, end));
    }

    public Optional<HumanTask> describeTask(TaskId taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        for (InstanceState state : support.instances.values()) {
            state.lock.lock();
            try {
                HumanTask task = state.tasks.get(taskId);
                if (task != null) {
                    return Optional.of(task);
                }
            } finally {
                state.lock.unlock();
            }
        }
        return Optional.empty();
    }

}
