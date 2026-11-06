package cn.byteforce.seedj.infra.local.workflow;

import cn.byteforce.seedj.workflow.spi.AssignmentStatus;
import cn.byteforce.seedj.workflow.spi.HumanTask;
import cn.byteforce.seedj.workflow.spi.HumanTaskNotFoundException;
import cn.byteforce.seedj.workflow.spi.HumanTaskSpec;
import cn.byteforce.seedj.workflow.spi.HumanTaskStateConflictException;
import cn.byteforce.seedj.workflow.spi.HumanTaskStatus;
import cn.byteforce.seedj.workflow.spi.ParticipantResolutionContext;
import cn.byteforce.seedj.workflow.spi.ParticipantResolutionException;
import cn.byteforce.seedj.workflow.spi.ParticipantSpec;
import cn.byteforce.seedj.workflow.spi.ParticipantType;
import cn.byteforce.seedj.workflow.spi.StepDefinition;
import cn.byteforce.seedj.workflow.spi.TaskAssignment;
import cn.byteforce.seedj.workflow.spi.TaskId;
import cn.byteforce.seedj.workflow.spi.WorkflowCapabilities;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinition;
import cn.byteforce.seedj.workflow.spi.WorkflowHistoryEvent;
import cn.byteforce.seedj.workflow.spi.WorkflowInstance;
import cn.byteforce.seedj.workflow.spi.WorkflowInstanceId;
import cn.byteforce.seedj.workflow.spi.WorkflowNotFoundException;
import cn.byteforce.seedj.workflow.spi.WorkflowProviderException;
import cn.byteforce.seedj.workflow.spi.WorkflowStateConflictException;
import cn.byteforce.seedj.id.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** 拆分后的内存工作流协作者：InMemoryWorkflowSupport。 */
final class InMemoryWorkflowSupport {

    static final Logger LOG = LoggerFactory.getLogger(InMemoryWorkflowSupport.class);
    static final String ADMIN = "admin";
    static final String REASON_UPSTREAM_ROLLBACK = "UPSTREAM_ROLLBACK";
    static final String REASON_RULE_SATISFIED = "RULE_SATISFIED";
    static final String REASON_REJECTED_TERMINATED = "REJECTED_TERMINATED";
    static final String REASON_AFTER_ADDED_SUSPENDED = "BEFORE_ADDED_SUSPENDED";

    final Clock clock;
    final ScheduledExecutorService scheduler;
    final boolean ownsScheduler;
    final IdGenerator<String> idGenerator;
    final ConcurrentHashMap<String, WorkflowDefinition> definitions = new ConcurrentHashMap<>();
    final ConcurrentHashMap<WorkflowInstanceId, InstanceState> instances = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, WorkflowInstanceId> actionIndex = new ConcurrentHashMap<>();
    final Object actionGate = new Object();
    final AtomicBoolean closed = new AtomicBoolean();
    InMemoryWorkflowSupport(Clock clock, ScheduledExecutorService scheduler, boolean ownsScheduler,
            IdGenerator<String> idGenerator) {
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
        this.ownsScheduler = ownsScheduler;
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    InstanceState requireInstance(WorkflowInstanceId id) {
        InstanceState state = instances.get(id);
        if (state == null) {
            throw new WorkflowNotFoundException("instance " + id.value() + " not found");
        }
        return state;
    }

    void requireLive(InstanceState state) {
        if (state.terminal()) {
            throw new WorkflowStateConflictException(
                    "instance " + state.id.value() + " is " + state.status);
        }
    }

    HumanTask requireTask(InstanceState state, TaskId taskId) {
        HumanTask task = state.tasks.get(taskId);
        if (task == null) {
            throw new HumanTaskNotFoundException("task " + taskId.value() + " not found");
        }
        return task;
    }

    HumanTask requirePendingTask(InstanceState state, TaskId taskId) {
        HumanTask task = requireTask(state, taskId);
        if (task.status() != HumanTaskStatus.PENDING) {
            throw new HumanTaskStateConflictException(
                    "task " + taskId.value() + " is " + task.status());
        }
        return task;
    }

    HumanTaskSpec specOf(InstanceState state, HumanTask task) {
        HumanTaskSpec spec = state.taskSpecs.get(task.id());
        if (spec != null) {
            return spec;
        }
        StepDefinition step = requireStep(state, task.stepName());
        if (step == null || step.humanTask() == null) {
            throw new WorkflowStateConflictException(
                    "step " + task.stepName() + " has no human task spec");
        }
        return step.humanTask();
    }

    StepDefinition requireCurrentStep(InstanceState state) {
        StepDefinition step = requireStep(state, state.currentStep);
        if (step == null) {
            throw new WorkflowStateConflictException(
                    "instance " + state.id.value() + " has no current step");
        }
        return step;
    }

    StepDefinition requireStep(InstanceState state, String stepName) {
        if (stepName == null) {
            return null;
        }
        return state.definition.steps().stream()
                .filter(s -> s.stepName().equals(stepName))
                .findFirst()
                .orElse(null);
    }

    int stepIndex(InstanceState state, String stepName) {
        List<StepDefinition> steps = state.definition.steps();
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).stepName().equals(stepName)) {
                return i;
            }
        }
        return -1;
    }

    boolean hasCompletedStepAfter(InstanceState state, int index) {
        List<StepDefinition> steps = state.definition.steps();
        for (int i = index + 1; i < steps.size(); i++) {
            if (state.completedSteps.contains(steps.get(i).stepName())) {
                return true;
            }
        }
        return false;
    }

    String nextStepName(WorkflowDefinition definition, String stepName) {
        List<StepDefinition> steps = definition.steps();
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).stepName().equals(stepName)) {
                return i + 1 < steps.size() ? steps.get(i + 1).stepName() : null;
            }
        }
        return null;
    }

    List<ParticipantSpec> resolveAssignees(InstanceState state, String stepName,
                                                   List<ParticipantSpec> assignees) {
        List<ParticipantSpec> resolved = new ArrayList<>();
        for (ParticipantSpec participant : assignees) {
            if (participant.type() != ParticipantType.EXPRESSION) {
                resolved.add(participant);
                continue;
            }
            List<ParticipantSpec> dynamic = resolveParticipants(
                    new ParticipantResolutionContext(participant.value(), state.id,
                            state.definition.id(), state.definition.version(), stepName,
                            Map.copyOf(state.variables), now()));
            if (dynamic == null || dynamic.isEmpty()) {
                throw new ParticipantResolutionException(
                        "expression '" + participant.value() + "' resolved to no participants");
            }
            resolved.addAll(dynamic);
        }
        if (resolved.isEmpty()) {
            throw new ParticipantResolutionException(
                    "step " + stepName + " resolved to no participants");
        }
        return resolved;
    }

    HumanTask withFormData(HumanTask task, Map<String, String> formData, Instant now) {
        Map<String, String> merged = new HashMap<>(task.formData());
        merged.putAll(formData);
        return new HumanTask(task.id(), task.instanceId(), task.stepName(), task.kind(),
                task.status(), task.assignments(), merged, task.predecessorTaskId(),
                task.successorTaskId(), task.spawnedByActionId(), task.createdAt(), now,
                task.completedAt(), task.dueAt(), task.metadata());
    }

    HumanTask withDueAt(HumanTask task, Instant dueAt, Instant now) {
        return new HumanTask(task.id(), task.instanceId(), task.stepName(), task.kind(),
                task.status(), task.assignments(), task.formData(), task.predecessorTaskId(),
                task.successorTaskId(), task.spawnedByActionId(), task.createdAt(), now,
                task.completedAt(), dueAt, task.metadata());
    }

    HumanTask withSuccessor(HumanTask task, TaskId successor, Instant now) {
        return new HumanTask(task.id(), task.instanceId(), task.stepName(), task.kind(),
                task.status(), task.assignments(), task.formData(), task.predecessorTaskId(),
                successor, task.spawnedByActionId(), task.createdAt(), now,
                task.completedAt(), task.dueAt(), task.metadata());
    }

    WorkflowInstance snapshot(InstanceState state) {
        List<TaskId> pendingTaskIds = state.tasks.values().stream()
                .filter(t -> t.status() == HumanTaskStatus.PENDING)
                .filter(t -> t.assignments().stream()
                        .anyMatch(a -> a.status() == AssignmentStatus.PENDING))
                .map(HumanTask::id)
                .toList();
        return new WorkflowInstance(state.id, state.definition.id(),
                state.definition.version(), state.status, state.currentStep,
                pendingTaskIds, state.startedBy, state.startedAt, state.updatedAt,
                state.completedAt, Map.copyOf(state.variables), state.failure);
    }

    void touch(InstanceState state) {
        state.updatedAt = now();
        state.version++;
    }

    void event(InstanceState state, String eventType, String stepName,
                       TaskId taskId, String actionId, Map<String, String> attributes) {
        state.events.add(new WorkflowHistoryEvent(idGenerator.id(), eventType,
                stepName, taskId == null ? null : taskId.value(), actionId, now(),
                attributes));
    }

    static Map<String, String> attrs(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(keyValues[i], nullSafe(keyValues[i + 1]));
        }
        return map;
    }

    static int pendingIndex(HumanTask task, String participant) {
        List<TaskAssignment> assignments = task.assignments();
        for (int i = 0; i < assignments.size(); i++) {
            TaskAssignment assignment = assignments.get(i);
            if (assignment.status() == AssignmentStatus.PENDING
                    && assignment.participant().value().equals(participant)) {
                return i;
            }
        }
        return -1;
    }

    static int assignmentIndex(HumanTask task, AssignmentStatus status,
                                       String participant) {
        List<TaskAssignment> assignments = task.assignments();
        for (int i = 0; i < assignments.size(); i++) {
            TaskAssignment assignment = assignments.get(i);
            if (assignment.status() == status
                    && assignment.participant().value().equals(participant)) {
                return i;
            }
        }
        return -1;
    }

    ProxyRef locateProxy(InstanceState state, HumanTask task, String assignee) {
        for (Delegation delegation : state.delegations) {
            if (delegation.handled || delegation.cancelled) {
                continue;
            }
            if (!delegation.taskId.equals(task.id())
                    || !delegation.proxyParticipant.equals(assignee)) {
                continue;
            }
            int originalIndex = assignmentIndex(task, AssignmentStatus.DELEGATED,
                    delegation.originalParticipant);
            int proxyIndex = pendingIndex(task, delegation.proxyParticipant);
            if (originalIndex >= 0 && proxyIndex >= 0) {
                return new ProxyRef(proxyIndex, delegation);
            }
        }
        return null;
    }

    static boolean hasPendingAssignment(HumanTask task, String participant) {
        return task.assignments().stream()
                .anyMatch(a -> a.status() == AssignmentStatus.PENDING
                        && a.participant().value().equals(participant));
    }

    static boolean hasCompletedBy(HumanTask task, String completedBy) {
        return task.assignments().stream()
                .filter(a -> a.status() == AssignmentStatus.COMPLETED)
                .map(TaskAssignment::record)
                .anyMatch(r -> r.assignee().equals(completedBy)
                        || (r.onBehalfOf() != null && r.onBehalfOf().equals(completedBy)));
    }

    static boolean hasCandidateGroup(HumanTask task, List<String> groups) {
        return task.assignments().stream()
                .filter(a -> a.status() == AssignmentStatus.PENDING)
                .map(TaskAssignment::participant)
                .anyMatch(p -> (p.type() == ParticipantType.GROUP
                        || p.type() == ParticipantType.DEPARTMENT)
                        && groups.contains(p.value()));
    }

    static String participantText(List<ParticipantSpec> participants) {
        return participants.stream()
                .map(p -> p.type().name() + ":" + p.value())
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    static int cursorOffset(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor));
        } catch (NumberFormatException e) {
            throw new WorkflowProviderException("invalid cursor: " + cursor, e);
        }
    }

    void requireCapability(boolean supported, String capability) {
        if (!supported) {
            throw new WorkflowStateConflictException(
                    "provider does not support " + capability);
        }
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }
    Instant now() {
        return clock.instant();
    }

    public List<ParticipantSpec> resolveParticipants(ParticipantResolutionContext context) {
        String expression = context.expression().strip();
        String key = expression;
        if (key.startsWith("${") && key.endsWith("}")) {
            key = key.substring(2, key.length() - 1);
        }
        String value = context.variables().get(key);
        if (value == null || value.isBlank()) {
            throw new ParticipantResolutionException(
                    "cannot resolve participant expression '" + expression + "'");
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(v -> !v.isEmpty())
                .distinct()
                .map(v -> new ParticipantSpec(ParticipantType.USER, v, Map.of()))
                .toList();
    }
    WorkflowCapabilities capabilities() {
        return WorkflowCapabilities.approval();
    }
}
