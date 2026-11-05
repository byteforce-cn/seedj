package cn.byteforce.seedj.workflow.memory;

import cn.byteforce.seedj.workflow.spi.ActionDecision;
import cn.byteforce.seedj.workflow.spi.ActionRequest;
import cn.byteforce.seedj.workflow.spi.AddAssigneeAction;
import cn.byteforce.seedj.workflow.spi.AddAssigneePosition;
import cn.byteforce.seedj.workflow.spi.AllApprove;
import cn.byteforce.seedj.workflow.spi.AnyApprove;
import cn.byteforce.seedj.workflow.spi.AnyRejectTerminates;
import cn.byteforce.seedj.workflow.spi.ApproveAction;
import cn.byteforce.seedj.workflow.spi.ApproveRecord;
import cn.byteforce.seedj.workflow.spi.AssignmentStatus;
import cn.byteforce.seedj.workflow.spi.AtLeastCount;
import cn.byteforce.seedj.workflow.spi.AtLeastPercentage;
import cn.byteforce.seedj.workflow.spi.AtLeastRejects;
import cn.byteforce.seedj.workflow.spi.AssigneeNotAllowedException;
import cn.byteforce.seedj.workflow.spi.CancelWorkflowRequest;
import cn.byteforce.seedj.workflow.spi.CcAction;
import cn.byteforce.seedj.workflow.spi.DelegateAction;
import cn.byteforce.seedj.workflow.spi.HumanTask;
import cn.byteforce.seedj.workflow.spi.HumanTaskKind;
import cn.byteforce.seedj.workflow.spi.HumanTaskNotFoundException;
import cn.byteforce.seedj.workflow.spi.HumanTaskSpec;
import cn.byteforce.seedj.workflow.spi.HumanTaskStateConflictException;
import cn.byteforce.seedj.workflow.spi.HumanTaskStatus;
import cn.byteforce.seedj.workflow.spi.ParticipantResolutionContext;
import cn.byteforce.seedj.workflow.spi.ParticipantResolutionException;
import cn.byteforce.seedj.workflow.spi.ParticipantSpec;
import cn.byteforce.seedj.workflow.spi.ParticipantType;
import cn.byteforce.seedj.workflow.spi.RecordAndContinue;
import cn.byteforce.seedj.workflow.spi.RejectAction;
import cn.byteforce.seedj.workflow.spi.RejectCompletion;
import cn.byteforce.seedj.workflow.spi.RejectTargetNotAllowedException;
import cn.byteforce.seedj.workflow.spi.RemoveAssigneeAction;
import cn.byteforce.seedj.workflow.spi.ResumeWorkflowAction;
import cn.byteforce.seedj.workflow.spi.RetryPolicy;
import cn.byteforce.seedj.workflow.spi.SignCompletion;
import cn.byteforce.seedj.workflow.spi.SignalAction;
import cn.byteforce.seedj.workflow.spi.StartWorkflowRequest;
import cn.byteforce.seedj.workflow.spi.StepDefinition;
import cn.byteforce.seedj.workflow.spi.StepKind;
import cn.byteforce.seedj.workflow.spi.TaskAssignment;
import cn.byteforce.seedj.workflow.spi.TaskId;
import cn.byteforce.seedj.workflow.spi.TaskQuery;
import cn.byteforce.seedj.workflow.spi.TerminateWorkflowRequest;
import cn.byteforce.seedj.workflow.spi.TransferAction;
import cn.byteforce.seedj.workflow.spi.UrgeAction;
import cn.byteforce.seedj.workflow.spi.WorkflowAction;
import cn.byteforce.seedj.workflow.spi.WorkflowCapabilities;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinition;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionId;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionNotFoundException;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionVersion;
import cn.byteforce.seedj.workflow.spi.WorkflowFailure;
import cn.byteforce.seedj.workflow.spi.WorkflowHistoryEvent;
import cn.byteforce.seedj.workflow.spi.WorkflowInstance;
import cn.byteforce.seedj.workflow.spi.WorkflowInstanceId;
import cn.byteforce.seedj.workflow.spi.WorkflowNotFoundException;
import cn.byteforce.seedj.workflow.spi.WorkflowProvider;
import cn.byteforce.seedj.workflow.spi.WorkflowProviderException;
import cn.byteforce.seedj.workflow.spi.WorkflowSearchQuery;
import cn.byteforce.seedj.workflow.spi.WorkflowStateConflictException;
import cn.byteforce.seedj.workflow.spi.WorkflowStatus;
import cn.byteforce.seedj.workflow.spi.WorkflowVersionConflictException;
import cn.byteforce.seedj.workflow.spi.WithdrawAction;
import cn.byteforce.seedj.workflow.spi.WithdrawInstanceAction;
import cn.byteforce.seedj.workflow.spi.WithdrawNotAllowedException;
import cn.byteforce.seedj.id.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内存实现：完整实现 v2.4 契约的组合语义，用于契约测试与其他 Provider 的参照。
 *
 * <p>语义要点：
 * <ul>
 *   <li>单进程，每实例一把锁；动作在锁内串行，重复 {@code actionId} 直接返回当前快照。</li>
 *   <li>{@code ACTIVITY}/{@code COMPENSATION} 在本实现中立即完成（没有外部执行器契约）。</li>
 *   <li>所有时间（超时、{@code dueAt}、委托到期）使用注入的 {@link Clock}。</li>
 *   <li>{@code close()} 只停止调度线程，不清空实例状态。</li>
 * </ul>
 */
public final class InMemoryWorkflowProvider implements WorkflowProvider {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryWorkflowProvider.class);

    private static final String ADMIN = "admin";
    private static final String REASON_UPSTREAM_ROLLBACK = "UPSTREAM_ROLLBACK";
    private static final String REASON_RULE_SATISFIED = "RULE_SATISFIED";
    private static final String REASON_REJECTED_TERMINATED = "REJECTED_TERMINATED";
    private static final String REASON_AFTER_ADDED_SUSPENDED = "BEFORE_ADDED_SUSPENDED";

    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final IdGenerator<String> idGenerator;

    private final ConcurrentHashMap<String, WorkflowDefinition> definitions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WorkflowInstanceId, InstanceState> instances = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WorkflowInstanceId> actionIndex = new ConcurrentHashMap<>();
    private final Object actionGate = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    public InMemoryWorkflowProvider() {
        this(Clock.systemUTC(), newScheduler(), true, localIdGenerator());
    }

    public InMemoryWorkflowProvider(Clock clock) {
        this(clock, newScheduler(), true, localIdGenerator());
    }

    public InMemoryWorkflowProvider(Clock clock, IdGenerator<String> idGenerator) {
        this(clock, newScheduler(), true, idGenerator == null ? localIdGenerator() : idGenerator);
    }

    public InMemoryWorkflowProvider(Clock clock, ScheduledExecutorService scheduler) {
        this(clock, scheduler, false, localIdGenerator());
    }

    private InMemoryWorkflowProvider(Clock clock, ScheduledExecutorService scheduler,
                                     boolean ownsScheduler, IdGenerator<String> idGenerator) {
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
        this.ownsScheduler = ownsScheduler;
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    private static IdGenerator<String> localIdGenerator() {
        String prefix = "wf-" + ProcessHandle.current().pid() + "-"
                + Long.toHexString(System.nanoTime());
        java.util.concurrent.atomic.AtomicLong sequence = new java.util.concurrent.atomic.AtomicLong();
        return () -> prefix + "-" + sequence.incrementAndGet();
    }

    private static ScheduledExecutorService newScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, runnable -> {
            Thread thread = new Thread(runnable, "seedj-workflow-memory");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    // ------------------------------------------------------------------
    // 定义管理
    // ------------------------------------------------------------------

    @Override
    public void register(WorkflowDefinition definition) {
        Objects.requireNonNull(definition, "definition cannot be null");
        definitions.put(definitionKey(definition.id(), definition.version()), definition);
    }

    @Override
    public Optional<WorkflowDefinition> findDefinition(WorkflowDefinitionId id,
                                                        WorkflowDefinitionVersion version) {
        Objects.requireNonNull(id, "id cannot be null");
        if (version != null) {
            return Optional.ofNullable(definitions.get(definitionKey(id, version)));
        }
        return definitions.values().stream()
                .filter(d -> d.id().equals(id))
                .max(Comparator.comparing(WorkflowDefinition::version,
                        Comparator.comparingInt(WorkflowDefinitionVersion::major)
                                .thenComparingInt(WorkflowDefinitionVersion::minor)));
    }

    // ------------------------------------------------------------------
    // 实例生命周期
    // ------------------------------------------------------------------

    @Override
    public WorkflowInstance start(StartWorkflowRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        synchronized (instances) {
            InstanceState existing = instances.get(request.instanceId());
            if (existing != null) {
                if (!existing.definition.id().equals(request.definitionId())) {
                    throw new WorkflowVersionConflictException(
                            "instance " + request.instanceId().value()
                                    + " already exists for definition "
                                    + existing.definition.id());
                }
                if (request.definitionVersion() != null
                        && !existing.definition.version().equals(request.definitionVersion())) {
                    throw new WorkflowVersionConflictException(
                            "instance " + request.instanceId().value()
                                    + " already exists for version "
                                    + existing.definition.version());
                }
                existing.lock.lock();
                try {
                    return snapshot(existing);
                } finally {
                    existing.lock.unlock();
                }
            }

            WorkflowDefinition definition = resolveDefinition(
                    request.definitionId(), request.definitionVersion());
            Instant now = now();
            InstanceState state = new InstanceState(request.instanceId(), definition,
                    request.startedBy(), now, now);
            state.variables.putAll(request.initialVariables());
            state.updatedAt = now;
            instances.put(state.id, state);

            state.lock.lock();
            try {
                event(state, "INSTANCE_STARTED", null, null, null,
                        attrs("definitionId", definition.id().name(),
                                "version", versionText(definition.version()),
                                "startedBy", request.startedBy()));
                if (definition.steps().isEmpty()) {
                    throw new WorkflowStateConflictException("definition has no steps");
                }
                enterStep(state, definition.steps().get(0).stepName(), null);
                scheduleWorkflowTimeout(state);
                touch(state);
                LOG.info("workflow started instanceId={} definitionId={} version={} step={}",
                        state.id.value(), definition.id(),
                        versionText(definition.version()), state.currentStep);
                return snapshot(state);
            } catch (RuntimeException failure) {
                instances.remove(state.id, state);
                throw failure;
            } finally {
                state.lock.unlock();
            }
        }
    }

    @Override
    public WorkflowInstance act(ActionRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        WorkflowAction action = request.action();
        synchronized (actionGate) {
            WorkflowInstanceId prior = actionIndex.get(action.actionId());
            if (prior != null) {
                return snapshot(requireInstance(prior));
            }
            InstanceState state = requireInstance(request.instanceId());
            state.lock.lock();
            try {
                if (state.terminal()) {
                    if (action instanceof UrgeAction) {
                        throw new HumanTaskStateConflictException(
                                "task is terminal for instance " + state.id.value());
                    }
                    throw new WorkflowStateConflictException(
                            "instance " + state.id.value() + " is " + state.status);
                }
                dispatch(state, action);
                state.actionIds.add(action.actionId());
                actionIndex.put(action.actionId(), state.id);
                touch(state);
                LOG.debug("workflow action instanceId={} definitionId={} actionId={} type={} "
                                + "step={}",
                        state.id.value(), state.definition.id(), action.actionId(),
                        action.getClass().getSimpleName(), state.currentStep);
                return snapshot(state);
            } finally {
                state.lock.unlock();
            }
        }
    }

    private void dispatch(InstanceState state, WorkflowAction action) {
        if (action instanceof SignalAction signal) {
            onSignal(state, signal);
        } else if (action instanceof ApproveAction approve) {
            onApprove(state, approve);
        } else if (action instanceof RejectAction reject) {
            onReject(state, reject);
        } else if (action instanceof AddAssigneeAction add) {
            onAddAssignee(state, add);
        } else if (action instanceof RemoveAssigneeAction remove) {
            onRemoveAssignee(state, remove);
        } else if (action instanceof TransferAction transfer) {
            onTransfer(state, transfer);
        } else if (action instanceof DelegateAction delegate) {
            onDelegate(state, delegate);
        } else if (action instanceof WithdrawAction withdraw) {
            onWithdraw(state, withdraw);
        } else if (action instanceof WithdrawInstanceAction withdraw) {
            onWithdrawInstance(state, withdraw);
        } else if (action instanceof UrgeAction urge) {
            onUrge(state, urge);
        } else if (action instanceof CcAction cc) {
            onCc(state, cc);
        } else if (action instanceof ResumeWorkflowAction resume) {
            onResume(state, resume);
        } else {
            throw new WorkflowStateConflictException(
                    "unsupported action: " + action.getClass().getName());
        }
    }

    @Override
    public WorkflowInstance cancel(CancelWorkflowRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        InstanceState state = requireInstance(request.instanceId());
        state.lock.lock();
        try {
            requireLive(state);
            if (request.compensate()) {
                state.status = WorkflowStatus.COMPENSATING;
            }
            cancelAllActiveTasks(state, "INSTANCE_CANCELLED");
            discardAllAfterPending(state, "UPSTREAM_ROLLBACK");
            state.status = WorkflowStatus.CANCELLED;
            state.completedAt = now();
            event(state, "INSTANCE_CANCELLED", state.currentStep, null, null,
                    attrs("reason", nullSafe(request.reason()),
                            "compensate", Boolean.toString(request.compensate())));
            touch(state);
            return snapshot(state);
        } finally {
            state.lock.unlock();
        }
    }

    @Override
    public WorkflowInstance terminate(TerminateWorkflowRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        InstanceState state = requireInstance(request.instanceId());
        state.lock.lock();
        try {
            requireLive(state);
            cancelAllActiveTasks(state, "TERMINATED");
            discardAllAfterPending(state, "UPSTREAM_ROLLBACK");
            state.status = WorkflowStatus.TERMINATED;
            state.completedAt = now();
            event(state, "INSTANCE_TERMINATED", state.currentStep, null, null,
                    attrs("reason", request.reason()));
            touch(state);
            return snapshot(state);
        } finally {
            state.lock.unlock();
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public Optional<WorkflowInstance> describe(WorkflowInstanceId instanceId) {
        InstanceState state = instances.get(instanceId);
        if (state == null) {
            return Optional.empty();
        }
        state.lock.lock();
        try {
            return Optional.of(snapshot(state));
        } finally {
            state.lock.unlock();
        }
    }

    @Override
    public List<WorkflowInstance> search(WorkflowSearchQuery query) {
        Objects.requireNonNull(query, "query cannot be null");
        List<WorkflowInstance> matches = new ArrayList<>();
        for (InstanceState state : instances.values()) {
            state.lock.lock();
            try {
                WorkflowInstance snapshot = snapshot(state);
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
        int offset = cursorOffset(query.cursor());
        if (offset >= matches.size()) {
            return List.of();
        }
        int end = Math.min(matches.size(), offset + query.limit());
        return List.copyOf(matches.subList(offset, end));
    }

    @Override
    public List<WorkflowHistoryEvent> history(WorkflowInstanceId instanceId) {
        InstanceState state = requireInstance(instanceId);
        state.lock.lock();
        try {
            return List.copyOf(state.events);
        } finally {
            state.lock.unlock();
        }
    }

    @Override
    public List<HumanTask> listTasks(TaskQuery query) {
        Objects.requireNonNull(query, "query cannot be null");
        List<HumanTask> matches = new ArrayList<>();
        for (InstanceState state : instances.values()) {
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
                            && !hasPendingAssignment(task, query.pendingFor())) {
                        continue;
                    }
                    if (query.completedBy() != null
                            && !hasCompletedBy(task, query.completedBy())) {
                        continue;
                    }
                    if (!query.candidateGroups().isEmpty()
                            && !hasCandidateGroup(task, query.candidateGroups())) {
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
        int offset = cursorOffset(query.cursor());
        if (offset >= matches.size()) {
            return List.of();
        }
        int end = Math.min(matches.size(), offset + query.limit());
        return List.copyOf(matches.subList(offset, end));
    }

    @Override
    public Optional<HumanTask> describeTask(TaskId taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        for (InstanceState state : instances.values()) {
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

    @Override
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

    @Override
    public Instant now() {
        return clock.instant();
    }

    @Override
    public WorkflowCapabilities capabilities() {
        return WorkflowCapabilities.approval();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // 动作实现
    // ------------------------------------------------------------------

    private void onSignal(InstanceState state, SignalAction action) {
        StepDefinition step = requireCurrentStep(state);
        if (step.kind() != StepKind.WAIT_SIGNAL) {
            throw new WorkflowStateConflictException(
                    "step " + step.stepName() + " is not WAIT_SIGNAL");
        }
        if (!Objects.equals(step.waitSignalName(), action.signalName())) {
            throw new WorkflowStateConflictException(
                    "signal '" + action.signalName() + "' does not match waitSignalName '"
                            + step.waitSignalName() + "'");
        }
        state.variables.putAll(action.payload());
        completeStepAndAdvance(state, step.stepName(), action.actionId());
    }

    private void onApprove(InstanceState state, ApproveAction action) {
        HumanTask task = requirePendingTask(state, action.taskId());
        ProxyRef proxy = locateProxy(state, task, action.assignee());
        int index;
        Delegation delegation;
        if (proxy != null) {
            index = proxy.index();
            delegation = proxy.delegation();
        } else {
            index = pendingIndex(task, action.assignee());
            delegation = null;
            if (index < 0) {
                throw new AssigneeNotAllowedException(
                        action.assignee() + " is not a pending assignee of task "
                                + task.id().value());
            }
        }
        Instant now = now();
        TaskAssignment assignment = task.assignments().get(index);
        String onBehalfOf = delegation == null ? null : delegation.originalParticipant;
        ApproveRecord record = new ApproveRecord(action.assignee(), onBehalfOf,
                ActionDecision.APPROVE, action.comment(), now, Map.of());
        HumanTask updated = TaskSupport.replace(task, index,
                TaskSupport.completed(assignment, record, now), now);
        updated = withFormData(updated, action.formData(), now);
        state.tasks.put(updated.id(), updated);
        evaluate(state, updated);
    }

    private void onReject(InstanceState state, RejectAction action) {
        HumanTask task = requirePendingTask(state, action.taskId());
        HumanTaskSpec spec = specOf(state, task);
        if (!spec.rejectTargets().contains(action.targetStep())) {
            throw new RejectTargetNotAllowedException(
                    "targetStep '" + action.targetStep() + "' is not in rejectTargets");
        }
        if (requireStep(state, action.targetStep()) == null) {
            throw new RejectTargetNotAllowedException(
                    "targetStep '" + action.targetStep() + "' does not exist");
        }

        int index;
        Delegation delegation;
        ProxyRef proxy = locateProxy(state, task, action.assignee());
        if (proxy != null) {
            index = proxy.index();
            delegation = proxy.delegation();
        } else {
            index = pendingIndex(task, action.assignee());
            delegation = null;
            if (index < 0) {
                throw new AssigneeNotAllowedException(
                        action.assignee() + " is not a pending assignee of task "
                                + task.id().value());
            }
        }

        Instant now = now();
        TaskAssignment assignment = task.assignments().get(index);
        String onBehalfOf = delegation == null ? null : delegation.originalParticipant;
        Map<String, String> extra = Map.of(
                "actionId", action.actionId(),
                "targetStep", action.targetStep());
        ApproveRecord record = new ApproveRecord(action.assignee(), onBehalfOf,
                ActionDecision.REJECT, action.comment(), now, extra);
        HumanTask updated = TaskSupport.replace(task, index,
                TaskSupport.completed(assignment, record, now), now);
        state.tasks.put(updated.id(), updated);
        state.lastRejectTarget.put(updated.id(), action.targetStep());

        RejectCompletion rule = spec.rejectCompletion();
        if (rule instanceof AnyRejectTerminates) {
            rejectTask(state, updated, action.targetStep(), REASON_REJECTED_TERMINATED,
                    action.actionId());
            return;
        }
        if (rule instanceof AtLeastRejects threshold
                && TaskSupport.rejects(updated) >= threshold.count()) {
            rejectTask(state, updated, action.targetStep(), "REJECT_THRESHOLD_REACHED",
                    action.actionId());
            return;
        }
        evaluate(state, updated);
    }

    private void onAddAssignee(InstanceState state, AddAssigneeAction action) {
        requireCapability(capabilities().supportsAddAssignee(), "add-assignee");
        HumanTask task = requireTask(state, action.taskId());
        HumanTaskSpec spec = specOf(state, task);
        if (!spec.allowAddAssignee()) {
            throw new WorkflowStateConflictException("add-assignee is not allowed for step "
                    + task.stepName());
        }
        if (!spec.allowedAddPositions().contains(action.position())) {
            throw new AssigneeNotAllowedException(
                    "position " + action.position() + " is not allowed for step " + task.stepName());
        }
        if (action.position() == AddAssigneePosition.BEFORE) {
            requireCapability(capabilities().supportsAddAssigneeBefore(), "add-assignee BEFORE");
            addBefore(state, task, spec, action);
        } else if (action.position() == AddAssigneePosition.AFTER) {
            requireCapability(capabilities().supportsAddAssigneeAfter(), "add-assignee AFTER");
            addAfter(state, task, spec, action);
        } else {
            addCurrent(state, task, action);
        }
    }

    private void addCurrent(InstanceState state, HumanTask task, AddAssigneeAction action) {
        if (task.status() != HumanTaskStatus.PENDING) {
            throw new HumanTaskStateConflictException("cannot add assignee to a task in status "
                    + task.status());
        }
        Instant now = now();
        List<ParticipantSpec> assignees = resolveAssignees(state, task.stepName(),
                action.newAssignees());
        List<TaskAssignment> extra = assignees.stream()
                .map(p -> new TaskAssignment(p, AssignmentStatus.PENDING, null, now, null, null))
                .toList();
        HumanTask updated = TaskSupport.append(task, extra, now);
        state.tasks.put(updated.id(), updated);
        event(state, "TASK_ASSIGNEE_ADDED", task.stepName(), task.id(), action.actionId(),
                attrs("operator", nullSafe(action.operator()),
                        "position", AddAssigneePosition.CURRENT.name(),
                        "assignees", participantText(assignees)));
    }

    private void addBefore(InstanceState state, HumanTask task, HumanTaskSpec spec,
                           AddAssigneeAction action) {
        if (task.status() != HumanTaskStatus.PENDING) {
            throw new HumanTaskStateConflictException(
                    "BEFORE add-assignee requires a PENDING task");
        }
        if (state.suspensions.containsKey(task.id())) {
            throw new WorkflowStateConflictException(
                    "task " + task.id().value() + " already has a BEFORE add-assignee in progress");
        }
        List<ParticipantSpec> assignees = resolveAssignees(state, task.stepName(),
                action.newAssignees());
        HumanTaskSpec derived = deriveSpec(spec, assignees, beforeSignCompletion(spec, assignees));

        Instant now = now();
        Suspension suspension = new Suspension(now, task.dueAt());
        List<TaskAssignment> assignments = new ArrayList<>();
        for (TaskAssignment assignment : task.assignments()) {
            if (assignment.status() == AssignmentStatus.PENDING) {
                assignments.add(new TaskAssignment(assignment.participant(),
                        AssignmentStatus.SUSPENDED, null, assignment.assignedAt(), now,
                        REASON_AFTER_ADDED_SUSPENDED));
                event(state, "TASK_ASSIGNMENT_SUSPENDED", task.stepName(), task.id(),
                        action.actionId(),
                        attrs("reason", REASON_AFTER_ADDED_SUSPENDED,
                                "participant", assignment.participant().value()));
            } else {
                assignments.add(assignment);
            }
        }
        HumanTask suspended = TaskSupport.withAssignments(task, assignments,
                HumanTaskStatus.PENDING, now, null);
        state.tasks.put(suspended.id(), suspended);
        state.suspensions.put(task.id(), suspension);

        Instant derivedDueAt = task.dueAt() == null ? null
                : now.plus(Duration.between(now, task.dueAt()).isNegative()
                        ? Duration.ZERO : Duration.between(now, task.dueAt()));
        HumanTask before = createTask(state, suspended.stepName(), derived,
                HumanTaskKind.BEFORE_ADDED, task.id(), action.actionId(), derivedDueAt);
        HumanTask original = withSuccessor(suspended, before.id(), now);
        state.tasks.put(original.id(), original);

        event(state, "TASK_ADD_ASSIGNEE_BEFORE", original.stepName(), original.id(),
                action.actionId(), attrs("operator", nullSafe(action.operator()),
                        "spawnedTaskId", before.id().value(),
                        "assignees", participantText(assignees)));
    }

    private void addAfter(InstanceState state, HumanTask task, HumanTaskSpec spec,
                          AddAssigneeAction action) {
        if (task.status() != HumanTaskStatus.PENDING
                && task.status() != HumanTaskStatus.COMPLETED) {
            throw new HumanTaskStateConflictException(
                    "AFTER add-assignee requires a PENDING or COMPLETED task");
        }
        List<ParticipantSpec> assignees = resolveAssignees(state, task.stepName(),
                action.newAssignees());
        if (task.status() == HumanTaskStatus.PENDING) {
            state.afterPending.add(new AfterPending(task.id(), assignees, action.actionId()));
            return;
        }
        spawnAfter(state, task, spec, new AfterPending(task.id(), assignees, action.actionId()));
    }

    private void onRemoveAssignee(InstanceState state, RemoveAssigneeAction action) {
        HumanTask task = requirePendingTask(state, action.taskId());
        HumanTaskSpec spec = specOf(state, task);
        if (!spec.allowRemoveAssignee()) {
            throw new WorkflowStateConflictException(
                    "remove-assignee is not allowed for step " + task.stepName());
        }
        Instant now = now();
        List<TaskAssignment> assignments = new ArrayList<>(task.assignments());
        List<String> removed = new ArrayList<>();
        for (String assignee : action.removedAssigneeIds()) {
            int index = pendingIndex(task, assignee);
            if (index < 0) {
                throw new WorkflowStateConflictException(
                        assignee + " is not a PENDING assignee of task " + task.id().value());
            }
            removed.add(assignee);
            assignments.set(index, new TaskAssignment(
                    task.assignments().get(index).participant(), AssignmentStatus.CANCELLED,
                    null, task.assignments().get(index).assignedAt(), now, "REMOVED"));
            cancelDelegationForProxy(state, task.id(), assignee);
            event(state, "TASK_ASSIGNMENT_AUTO_CANCELLED", task.stepName(), task.id(),
                    action.actionId(), attrs("reason", "REMOVED", "participant", assignee));
        }
        HumanTask updated = TaskSupport.withAssignments(task, assignments,
                HumanTaskStatus.PENDING, now, null);
        state.tasks.put(updated.id(), updated);
        event(state, "TASK_ASSIGNEE_REMOVED", updated.stepName(), updated.id(),
                action.actionId(), attrs("operator", nullSafe(action.operator()),
                        "assignees", String.join(",", removed)));
        evaluate(state, updated);
    }

    private void onTransfer(InstanceState state, TransferAction action) {
        requireCapability(capabilities().supportsTransfer(), "transfer");
        HumanTask task = requirePendingTask(state, action.taskId());
        HumanTaskSpec spec = specOf(state, task);
        if (!spec.allowTransfer()) {
            throw new WorkflowStateConflictException(
                    "transfer is not allowed for step " + task.stepName());
        }
        int index = pendingIndex(task, action.fromAssignee());
        if (index < 0) {
            throw new AssigneeNotAllowedException(
                    action.fromAssignee() + " is not a PENDING assignee of task " + task.id().value());
        }
        Instant now = now();
        List<TaskAssignment> assignments = new ArrayList<>(task.assignments());
        TaskAssignment original = assignments.get(index);
        assignments.set(index, new TaskAssignment(original.participant(),
                AssignmentStatus.TRANSFERRED, null, original.assignedAt(), now, null));
        cancelDelegationForProxy(state, task.id(), action.fromAssignee());
        assignments.add(new TaskAssignment(action.toAssignee(), AssignmentStatus.PENDING,
                null, now, null, null));
        HumanTask updated = TaskSupport.withAssignments(task, assignments,
                HumanTaskStatus.PENDING, now, null);
        state.tasks.put(updated.id(), updated);
        event(state, "TASK_TRANSFERRED", updated.stepName(), updated.id(), action.actionId(),
                attrs("fromAssignee", action.fromAssignee(),
                        "toAssignee", action.toAssignee().value(),
                        "reason", nullSafe(action.reason())));
    }

    private void onDelegate(InstanceState state, DelegateAction action) {
        requireCapability(capabilities().supportsDelegate(), "delegate");
        HumanTask task = requirePendingTask(state, action.taskId());
        HumanTaskSpec spec = specOf(state, task);
        if (!spec.allowDelegate()) {
            throw new WorkflowStateConflictException(
                    "delegate is not allowed for step " + task.stepName());
        }
        int index = pendingIndex(task, action.fromAssignee());
        if (index < 0) {
            throw new AssigneeNotAllowedException(
                    action.fromAssignee() + " is not a PENDING assignee of task " + task.id().value());
        }
        boolean nested = state.delegations.stream()
                .anyMatch(d -> !d.handled && !d.cancelled
                        && d.taskId.equals(task.id())
                        && d.proxyParticipant.equals(action.fromAssignee()));
        if (nested) {
            throw new WorkflowStateConflictException("nested delegation is not allowed");
        }
        Instant now = now();
        Instant expiresAt = now.plus(action.delegateFor());
        List<TaskAssignment> assignments = new ArrayList<>(task.assignments());
        TaskAssignment original = assignments.get(index);
        assignments.set(index, new TaskAssignment(original.participant(),
                AssignmentStatus.DELEGATED, null, original.assignedAt(), null, null));
        assignments.add(new TaskAssignment(action.toAssignee(), AssignmentStatus.PENDING,
                null, now, null, null));
        HumanTask updated = TaskSupport.withAssignments(task, assignments,
                HumanTaskStatus.PENDING, now, null);
        state.tasks.put(updated.id(), updated);

        Delegation delegation = new Delegation(task.id(), action.fromAssignee(),
                action.toAssignee().value(), expiresAt, action.actionId());
        state.delegations.add(delegation);
        schedule(() -> onDelegationExpired(state.id, delegation), expiresAt);

        event(state, "TASK_DELEGATED", updated.stepName(), updated.id(), action.actionId(),
                attrs("fromAssignee", action.fromAssignee(),
                        "toAssignee", action.toAssignee().value(),
                        "expiresAt", expiresAt.toString(),
                        "reason", nullSafe(action.reason())));
    }

    private void onWithdraw(InstanceState state, WithdrawAction action) {
        requireCapability(capabilities().supportsWithdraw(), "withdraw");
        HumanTask task = requireTask(state, action.taskId());
        if (task.status() != HumanTaskStatus.PENDING) {
            throw new WorkflowStateConflictException(
                    "cannot withdraw a task in status " + task.status());
        }
        HumanTaskSpec spec = specOf(state, task);
        if (!spec.allowWithdraw()) {
            throw new WorkflowStateConflictException(
                    "withdraw is not allowed for step " + task.stepName());
        }
        boolean rejectedByOperator = task.assignments().stream()
                .filter(a -> a.status() == AssignmentStatus.COMPLETED)
                .map(TaskAssignment::record)
                .anyMatch(r -> r.assignee().equals(action.operator())
                        && r.decision() == ActionDecision.REJECT);
        if (rejectedByOperator) {
            throw new WorkflowStateConflictException("cannot withdraw a REJECT record");
        }
        boolean approvedByOperator = task.assignments().stream()
                .filter(a -> a.status() == AssignmentStatus.COMPLETED)
                .map(TaskAssignment::record)
                .anyMatch(r -> r.assignee().equals(action.operator())
                        && r.decision() == ActionDecision.APPROVE);
        if (!approvedByOperator) {
            throw new WithdrawNotAllowedException(
                    action.operator() + " has no APPROVE record on task " + task.id().value());
        }

        int targetIndex = stepIndex(state, action.targetStep());
        int currentIndex = stepIndex(state, task.stepName());
        if (targetIndex < 0 || targetIndex > currentIndex) {
            throw new WithdrawNotAllowedException(
                    "targetStep '" + action.targetStep() + "' is not upstream of "
                            + task.stepName());
        }
        if (hasCompletedStepAfter(state, currentIndex)) {
            throw new WithdrawNotAllowedException(
                    "downstream step has already completed");
        }

        Instant now = now();
        List<TaskAssignment> assignments = cancelActiveAssignments(state, task,
                "WITHDRAWN", now);
        HumanTask withdrawn = TaskSupport.withAssignments(task, assignments,
                HumanTaskStatus.WITHDRAWN, now, now);
        state.tasks.put(withdrawn.id(), withdrawn);
        event(state, "TASK_WITHDRAWN", withdrawn.stepName(), withdrawn.id(), action.actionId(),
                attrs("operator", action.operator(),
                        "withdrawnRecordAssignee", action.operator(),
                        "targetStep", action.targetStep(),
                        "reason", nullSafe(action.reason())));
        discardAfterPending(state, withdrawn.id(), "WITHDRAWN");
        rollback(state, action.targetStep(), withdrawn.stepName(), action.actionId(),
                "WITHDRAWN");
    }

    private void onWithdrawInstance(InstanceState state, WithdrawInstanceAction action) {
        requireCapability(capabilities().supportsWithdrawInstance(), "withdraw-instance");
        if (!state.startedBy.equals(action.operator())) {
            throw new WithdrawNotAllowedException(
                    "only startedBy can withdraw the whole instance");
        }
        Instant now = now();
        for (HumanTask task : new ArrayList<>(state.tasks.values())) {
            if (task.status() != HumanTaskStatus.PENDING) {
                continue;
            }
            List<TaskAssignment> assignments = cancelActiveAssignments(state, task,
                    "WITHDRAWN", now);
            HumanTask withdrawn = TaskSupport.withAssignments(task, assignments,
                    HumanTaskStatus.WITHDRAWN, now, now);
            state.tasks.put(withdrawn.id(), withdrawn);
            event(state, "TASK_WITHDRAWN", withdrawn.stepName(), withdrawn.id(),
                    action.actionId(), attrs("operator", action.operator(),
                            "targetStep", action.targetStep(),
                            "reason", nullSafe(action.reason())));
            discardAfterPending(state, withdrawn.id(), "WITHDRAWN");
        }
        discardAllAfterPending(state, "WITHDRAWN");
        state.status = WorkflowStatus.CANCELLED;
        state.completedAt = now;
        event(state, "INSTANCE_WITHDRAWN", state.currentStep, null, action.actionId(),
                attrs("operator", action.operator(),
                        "targetStep", action.targetStep(),
                        "reason", nullSafe(action.reason())));
        event(state, "INSTANCE_ROLLED_BACK", state.currentStep, null, action.actionId(),
                attrs("targetStep", action.targetStep(),
                        "byAction", action.actionId()));
    }

    private void onUrge(InstanceState state, UrgeAction action) {
        requireCapability(capabilities().supportsUrge(), "urge");
        HumanTask task = requireTask(state, action.taskId());
        if (task.status() != HumanTaskStatus.PENDING) {
            throw new HumanTaskStateConflictException(
                    "cannot urge a task in status " + task.status());
        }
        HumanTaskSpec spec = specOf(state, task);
        if (!spec.allowUrge()) {
            throw new WorkflowStateConflictException(
                    "urge is not allowed for step " + task.stepName());
        }
        boolean participant = task.assignments().stream()
                .anyMatch(a -> a.participant().value().equals(action.operator()));
        if (!participant && !ADMIN.equals(action.operator())) {
            throw new AssigneeNotAllowedException(
                    action.operator() + " is not a participant of task " + task.id().value());
        }
        boolean suspended = task.assignments().stream()
                .anyMatch(a -> a.status() == AssignmentStatus.SUSPENDED);
        event(state, "TASK_URGED", task.stepName(), task.id(), action.actionId(),
                attrs("operator", action.operator(),
                        "message", nullSafe(action.message()),
                        "targetStatus", suspended ? "SUSPENDED" : "PENDING"));
    }

    private void onCc(InstanceState state, CcAction action) {
        requireCapability(capabilities().supportsCc(), "cc");
        if (action.stepName() != null) {
            StepDefinition step = requireStep(state, action.stepName());
            if (step == null || step.kind() != StepKind.HUMAN_TASK
                    || !step.humanTask().allowCc()) {
                throw new WorkflowStateConflictException(
                        "cc is not allowed for step " + action.stepName());
            }
        }
        event(state, "TASK_CC", action.stepName(), null, action.actionId(),
                attrs("operator", action.operator(),
                        "recipients", participantText(action.recipients()),
                        "message", nullSafe(action.message())));
    }

    private void onResume(InstanceState state, ResumeWorkflowAction action) {
        requireCapability(capabilities().supportsResumeWorkflow(), "resume");
        if (state.status != WorkflowStatus.WAITING) {
            throw new WorkflowStateConflictException(
                    "resume requires WAITING instance, current status is " + state.status);
        }
        if (!state.startedBy.equals(action.operator()) && !ADMIN.equals(action.operator())) {
            throw new WorkflowStateConflictException(
                    "only startedBy or an administrator can resume the instance");
        }
        StepDefinition step = requireCurrentStep(state);
        if (step.kind() != StepKind.HUMAN_TASK) {
            throw new WorkflowStateConflictException(
                    "resume requires a HUMAN_TASK step, current is " + step.kind());
        }
        HumanTaskSpec spec = step.humanTask();
        Duration effectiveDueIn = action.newDueIn() != null ? action.newDueIn() : spec.dueIn();
        Instant startedAt = state.stepStartedAt.get(step.stepName());
        Duration timeout = stepTimeout(state, step);
        if (timeout != null && effectiveDueIn != null && startedAt != null) {
            Duration elapsed = Duration.between(startedAt, now());
            if (elapsed.isNegative()) {
                elapsed = Duration.ZERO;
            }
            if (elapsed.plus(effectiveDueIn).compareTo(timeout) > 0) {
                throw new WorkflowStateConflictException(
                        "remaining step timeout budget is insufficient for newDueIn");
            }
        }
        Instant now = now();
        state.status = WorkflowStatus.RUNNING;
        event(state, "INSTANCE_RESUMED", step.stepName(), null, action.actionId(),
                attrs("operator", action.operator(),
                        "newDueIn", effectiveDueIn == null ? "" : effectiveDueIn.toString(),
                        "reason", nullSafe(action.reason())));
        Instant dueAt = effectiveDueIn == null ? null : now.plus(effectiveDueIn);
        createTask(state, step.stepName(), spec, HumanTaskKind.ORIGINAL, null,
                action.actionId(), dueAt);
    }

    // ------------------------------------------------------------------
    // 会签 / 驳回 / 终态
    // ------------------------------------------------------------------

    private void evaluate(InstanceState state, HumanTask task) {
        if (task.status() != HumanTaskStatus.PENDING) {
            return;
        }
        HumanTaskSpec spec = specOf(state, task);
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

    private void completeTaskSatisfied(InstanceState state, HumanTask task) {
        Instant now = now();
        List<TaskAssignment> assignments = cancelActiveAssignments(state, task,
                REASON_RULE_SATISFIED, now);
        HumanTask completed = TaskSupport.withAssignments(task, assignments,
                HumanTaskStatus.COMPLETED, now, now);
        state.tasks.put(completed.id(), completed);
        state.suspensions.remove(completed.id());
        event(state, "TASK_COMPLETED", completed.stepName(), completed.id(), null,
                attrs("assignees", participantText(completed.pendingAssignees().isEmpty()
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

    private void rejectTask(InstanceState state, HumanTask task, String targetStep,
                            String reason, String actionId) {
        Instant now = now();
        List<TaskAssignment> assignments = cancelActiveAssignments(state, task, reason, now);
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
        event(state, "TASK_REJECTED", rejected.stepName(), rejected.id(), actionId,
                attrs("reason", reason,
                        "rejectActionIds", rejectActionIds));
        discardAfterPending(state, rejected.id(), "REJECTED");
        rollback(state, targetStep, rejected.stepName(), actionId, reason);
    }

    private void failUnsatisfiable(InstanceState state, HumanTask task) {
        Instant now = now();
        List<TaskAssignment> assignments = cancelActiveAssignments(state, task,
                "SIGN_COMPLETION_UNSATISFIABLE", now);
        HumanTask cancelled = TaskSupport.withAssignments(task, assignments,
                HumanTaskStatus.CANCELLED, now, now);
        state.tasks.put(cancelled.id(), cancelled);
        event(state, "TASK_CANCELLED", cancelled.stepName(), cancelled.id(), null,
                attrs("reason", "SIGN_COMPLETION_UNSATISFIABLE"));
        discardAfterPending(state, cancelled.id(), "UPSTREAM_ROLLBACK");
        HumanTaskSpec spec = specOf(state, cancelled);
        int required = requiredCount(spec.signCompletion(), TaskSupport.denominator(cancelled));
        failInstance(state, "SIGN_COMPLETION_UNSATISFIABLE",
                "approval rule cannot be satisfied for step " + cancelled.stepName(),
                cancelled.stepName(),
                Map.of("approveCount", Integer.toString(TaskSupport.approvals(cancelled)),
                        "requiredCount", Integer.toString(required)));
    }

    private void rollback(InstanceState state, String targetStep, String fromStep,
                          String actionId, String reason) {
        int targetIndex = stepIndex(state, targetStep);
        if (targetIndex < 0) {
            throw new WorkflowStateConflictException("unknown targetStep '" + targetStep + "'");
        }
        Instant now = now();
        for (HumanTask candidate : new ArrayList<>(state.tasks.values())) {
            if (candidate.status() != HumanTaskStatus.PENDING) {
                continue;
            }
            int index = stepIndex(state, candidate.stepName());
            if (index >= targetIndex) {
                cancelTask(state, candidate, REASON_UPSTREAM_ROLLBACK,
                        REASON_UPSTREAM_ROLLBACK, now);
            }
        }
        state.completedSteps.removeIf(step -> {
            int index = stepIndex(state, step);
            return index >= targetIndex;
        });
        state.afterPending.removeIf(p -> {
            HumanTask original = state.tasks.get(p.originalTaskId());
            return original == null || stepIndex(state, original.stepName()) >= targetIndex;
        });
        state.currentStep = targetStep;
        event(state, "INSTANCE_ROLLED_BACK", targetStep, null, actionId,
                attrs("targetStep", targetStep,
                        "fromStep", fromStep,
                        "reason", reason,
                        "byAction", actionId));
        enterStep(state, targetStep, actionId);
    }

    private void failInstance(InstanceState state, String errorType, String message,
                              String failedStep, Map<String, String> details) {
        Instant now = now();
        for (HumanTask task : new ArrayList<>(state.tasks.values())) {
            if (task.status() == HumanTaskStatus.PENDING) {
                cancelTask(state, task, errorType, errorType, now);
            }
        }
        state.status = WorkflowStatus.FAILED;
        state.completedAt = now;
        state.failure = new WorkflowFailure(errorType, message, false, failedStep, details);
        event(state, "INSTANCE_FAILED", failedStep, null, null,
                attrs("errorType", errorType,
                        "message", nullSafe(message)));
        LOG.warn("workflow failed instanceId={} definitionId={} version={} step={} "
                        + "errorType={} message={}",
                state.id.value(), state.definition.id(),
                versionText(state.definition.version()), failedStep, errorType, message);
    }

    // ------------------------------------------------------------------
    // 超时 / 委托到期
    // ------------------------------------------------------------------

    private void onTaskDue(WorkflowInstanceId instanceId, TaskId taskId, Instant firedDueAt) {
        InstanceState state = instances.get(instanceId);
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
            touch(state);
        } finally {
            state.lock.unlock();
        }
    }

    private void handleDueTimeout(InstanceState state, HumanTask task) {
        Instant now = now();
        List<TaskAssignment> assignments = cancelActiveAssignments(state, task,
                "TASK_OVERDUE", now);
        HumanTask cancelled = TaskSupport.withAssignments(task, assignments,
                HumanTaskStatus.CANCELLED, now, now);
        state.tasks.put(cancelled.id(), cancelled);
        state.suspensions.remove(cancelled.id());
        event(state, "TASK_OVERDUE", cancelled.stepName(), cancelled.id(), null,
                attrs("reason", "TASK_OVERDUE"));
        discardAfterPending(state, cancelled.id(), "TASK_OVERDUE");
        HumanTaskSpec spec = specOf(state, cancelled);
        String policy = spec.metadata().getOrDefault("timeoutPolicy", "FAILED");
        if ("WAITING".equalsIgnoreCase(policy)) {
            state.status = WorkflowStatus.WAITING;
            state.failure = null;
            event(state, "INSTANCE_WAITING", cancelled.stepName(), cancelled.id(), null,
                    attrs("reason", "TASK_OVERDUE"));
        } else {
            failInstance(state, "TASK_OVERDUE",
                    "human task overdue at step " + cancelled.stepName(),
                    cancelled.stepName(),
                    Map.of("taskId", cancelled.id().value()));
        }
    }

    private void onStepTimeout(InstanceState state, String stepName, Instant startedAt) {
        if (state.terminal()) {
            return;
        }
        if (!Objects.equals(state.currentStep, stepName)) {
            return;
        }
        if (!Objects.equals(state.stepStartedAt.get(stepName), startedAt)) {
            return;
        }
        Instant now = now();
        if (state.status == WorkflowStatus.WAITING) {
            failInstance(state, "STEP_TIMEOUT",
                    "step timeout reached while instance was WAITING", stepName,
                    Map.of("step", stepName));
            touch(state);
            return;
        }
        for (HumanTask task : new ArrayList<>(state.tasks.values())) {
            if (task.status() == HumanTaskStatus.PENDING
                    && task.stepName().equals(stepName)) {
                cancelTask(state, task, "STEP_TIMEOUT", "STEP_TIMEOUT", now);
            }
        }
        failInstance(state, "STEP_TIMEOUT",
                "step timeout reached for " + stepName, stepName,
                Map.of("step", stepName));
        touch(state);
    }

    private void onWorkflowTimeout(InstanceState state) {
        if (state.terminal()) {
            return;
        }
        Instant now = now();
        cancelAllActiveTasks(state, "WORKFLOW_TIMEOUT");
        discardAllAfterPending(state, "UPSTREAM_ROLLBACK");
        failInstance(state, "WORKFLOW_TIMEOUT",
                "workflow timeout reached", state.currentStep,
                Map.of("definitionId", state.definition.id().name()));
        touch(state);
    }

    private void onDelegationExpired(WorkflowInstanceId instanceId, Delegation delegation) {
        InstanceState state = instances.get(instanceId);
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
            int originalIndex = assignmentIndex(task, AssignmentStatus.DELEGATED,
                    delegation.originalParticipant);
            int proxyIndex = pendingIndex(task, delegation.proxyParticipant);
            Instant now = now();
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
                event(state, "TASK_ASSIGNMENT_AUTO_CANCELLED", task.stepName(), task.id(),
                        delegation.actionId,
                        attrs("reason", "DELEGATION_EXPIRED",
                                "participant", delegation.proxyParticipant));
                TaskAssignment original = assignments.get(originalIndex);
                assignments.set(originalIndex, new TaskAssignment(original.participant(),
                        AssignmentStatus.PENDING, null, original.assignedAt(), null, null));
                HumanTask updated = TaskSupport.withAssignments(task, assignments,
                        HumanTaskStatus.PENDING, now, null);
                state.tasks.put(updated.id(), updated);
                delegation.handled = true;
                event(state, "DELEGATION_EXPIRED", task.stepName(), task.id(),
                        delegation.actionId,
                        attrs("participant", delegation.originalParticipant,
                                "proxy", delegation.proxyParticipant));
                scheduleTaskDue(state, updated);
                evaluate(state, updated);
            } else {
                boolean proxyCompleted = task.assignments().stream()
                        .anyMatch(a -> a.status() == AssignmentStatus.COMPLETED
                                && a.participant().value().equals(delegation.proxyParticipant)
                                && delegation.originalParticipant.equals(
                                        a.record() == null ? null : a.record().onBehalfOf()));
                delegation.handled = true;
                if (proxyCompleted) {
                    event(state, "DELEGATION_EXPIRED_NOOP", task.stepName(), task.id(),
                            delegation.actionId,
                            attrs("participant", delegation.originalParticipant,
                                    "proxy", delegation.proxyParticipant));
                }
            }
            touch(state);
        } finally {
            state.lock.unlock();
        }
    }

    // ------------------------------------------------------------------
    // 步骤 / 任务推进
    // ------------------------------------------------------------------

    private void enterStep(InstanceState state, String stepName, String actionId) {
        if (stepName == null) {
            completeInstance(state);
            return;
        }
        StepDefinition step = requireStep(state, stepName);
        if (step == null) {
            throw new WorkflowStateConflictException("unknown step '" + stepName + "'");
        }
        Instant now = now();
        state.currentStep = stepName;
        state.status = WorkflowStatus.RUNNING;
        state.stepStartedAt.put(stepName, now);
        Duration timeout = stepTimeout(state, step);
        if (timeout != null) {
            state.stepDeadline.put(stepName, now.plus(timeout));
        }
        event(state, "STEP_STARTED", stepName, null, actionId,
                attrs("kind", step.kind().name()));
        scheduleStepTimeout(state, step, now, timeout);

        if (step.kind() == StepKind.HUMAN_TASK) {
            createTask(state, stepName, step.humanTask(), HumanTaskKind.ORIGINAL,
                    null, actionId, null);
            return;
        }
        if (step.kind() == StepKind.WAIT_SIGNAL) {
            return;
        }
        // ACTIVITY / COMPENSATION：内存实现没有外部执行器契约，立即完成。
        Instant doneAt = now();
        state.completedSteps.add(stepName);
        event(state, "STEP_COMPLETED", stepName, null, actionId, Map.of());
        enterStep(state, nextStepName(state.definition, stepName), actionId);
        state.updatedAt = doneAt;
    }

    private void maybeAdvance(InstanceState state) {
        if (state.terminal()) {
            return;
        }
        String stepName = state.currentStep;
        if (stepName == null) {
            return;
        }
        StepDefinition step = requireStep(state, stepName);
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
            event(state, "STEP_COMPLETED", stepName, null, null, Map.of());
        }
        enterStep(state, nextStepName(state.definition, stepName), null);
    }

    private void completeStepAndAdvance(InstanceState state, String stepName, String actionId) {
        state.completedSteps.add(stepName);
        event(state, "STEP_COMPLETED", stepName, null, actionId, Map.of());
        enterStep(state, nextStepName(state.definition, stepName), actionId);
    }

    private void completeInstance(InstanceState state) {
        state.currentStep = null;
        state.status = WorkflowStatus.COMPLETED;
        state.completedAt = now();
        event(state, "INSTANCE_COMPLETED", null, null, null, Map.of());
        LOG.info("workflow completed instanceId={} definitionId={} version={}",
                state.id.value(), state.definition.id(),
                versionText(state.definition.version()));
    }

    private HumanTask createTask(InstanceState state, String stepName, HumanTaskSpec spec,
                                 HumanTaskKind kind, TaskId predecessor, String actionId,
                                 Instant dueAtOverride) {
        Instant now = now();
        List<ParticipantSpec> assignees = resolveAssignees(state, stepName, spec.assignees());
        List<TaskAssignment> assignments = assignees.stream()
                .map(p -> new TaskAssignment(p, AssignmentStatus.PENDING, null, now, null, null))
                .toList();
        Instant dueAt = dueAtOverride != null ? dueAtOverride
                : (spec.dueIn() == null ? null : now.plus(spec.dueIn()));
        HumanTask task = new HumanTask(new TaskId("task-" + idGenerator.id()),
                state.id, stepName, kind, HumanTaskStatus.PENDING, assignments, Map.of(),
                predecessor, null, actionId, now, now, null, dueAt, Map.of());
        state.tasks.put(task.id(), task);
        state.taskSpecs.put(task.id(), spec);
        event(state, "TASK_CREATED", stepName, task.id(), actionId,
                attrs("kind", kind.name(),
                        "assignees", participantText(assignees),
                        "dueAt", dueAt == null ? "" : dueAt.toString()));
        scheduleTaskDue(state, task);
        return task;
    }

    private void resumePredecessor(InstanceState state, HumanTask beforeTask) {
        TaskId predecessorId = beforeTask.predecessorTaskId();
        if (predecessorId == null) {
            return;
        }
        HumanTask original = state.tasks.get(predecessorId);
        if (original == null || original.status() != HumanTaskStatus.PENDING) {
            return;
        }
        Suspension suspension = state.suspensions.remove(original.id());
        Instant now = now();
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
        updated = withDueAt(updated, newDueAt, now);
        state.tasks.put(updated.id(), updated);
        for (String participant : resumed) {
            event(state, "TASK_ASSIGNMENT_RESUMED", updated.stepName(), updated.id(), null,
                    attrs("participant", participant,
                            "originalDueAt", original.dueAt() == null ? ""
                                    : original.dueAt().toString(),
                            "newDueAt", newDueAt == null ? "" : newDueAt.toString()));
        }
        scheduleTaskDue(state, updated);
        evaluate(state, updated);
    }

    private void spawnAllAfterPending(InstanceState state, HumanTask original) {
        List<AfterPending> pending = state.afterPending.stream()
                .filter(p -> p.originalTaskId().equals(original.id()))
                .toList();
        state.afterPending.removeAll(pending);
        HumanTaskSpec spec = specOf(state, original);
        for (AfterPending after : pending) {
            spawnAfter(state, original, spec, after);
        }
    }

    private void spawnAfter(InstanceState state, HumanTask original, HumanTaskSpec spec,
                            AfterPending after) {
        Instant now = now();
        HumanTaskSpec derived = deriveSpec(spec, after.assignees(),
                afterSignCompletion(spec, after.assignees()));
        HumanTask spawned = createTask(state, original.stepName(), derived,
                HumanTaskKind.AFTER_ADDED, original.id(), after.actionId(), null);
        HumanTask updatedOriginal = withSuccessor(original, spawned.id(), now);
        state.tasks.put(updatedOriginal.id(), updatedOriginal);
        if (state.currentStep != null) {
            int currentIndex = stepIndex(state, state.currentStep);
            int originalIndex = stepIndex(state, original.stepName());
            if (currentIndex <= originalIndex) {
                state.currentStep = original.stepName();
            }
        }
        event(state, "TASK_ADD_ASSIGNEE_AFTER", original.stepName(), updatedOriginal.id(),
                after.actionId(), attrs("spawnedTaskId", spawned.id().value(),
                        "assignees", participantText(after.assignees())));
    }

    // ------------------------------------------------------------------
    // 取消 / 派生规格
    // ------------------------------------------------------------------

    private List<TaskAssignment> cancelActiveAssignments(InstanceState state, HumanTask task,
                                                         String reason, Instant now) {
        List<TaskAssignment> assignments = new ArrayList<>();
        for (TaskAssignment assignment : task.assignments()) {
            if (TaskSupport.active(assignment.status())) {
                assignments.add(TaskSupport.cancelled(assignment, reason, now));
                event(state, "TASK_ASSIGNMENT_AUTO_CANCELLED", task.stepName(), task.id(),
                        null, attrs("reason", reason,
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

    private void cancelTask(InstanceState state, HumanTask task, String assignmentReason,
                            String eventReason, Instant now) {
        List<TaskAssignment> assignments = cancelActiveAssignments(state, task,
                assignmentReason, now);
        HumanTask cancelled = TaskSupport.withAssignments(task, assignments,
                HumanTaskStatus.CANCELLED, now, now);
        state.tasks.put(cancelled.id(), cancelled);
        state.suspensions.remove(cancelled.id());
        event(state, "TASK_CANCELLED", cancelled.stepName(), cancelled.id(), null,
                attrs("reason", eventReason));
        discardAfterPending(state, cancelled.id(), REASON_UPSTREAM_ROLLBACK);
    }

    private void cancelAllActiveTasks(InstanceState state, String reason) {
        Instant now = now();
        for (HumanTask task : new ArrayList<>(state.tasks.values())) {
            if (task.status() == HumanTaskStatus.PENDING) {
                cancelTask(state, task, reason, reason, now);
            }
        }
    }

    private void cancelDelegationsFor(InstanceState state, TaskId taskId) {
        for (Delegation delegation : state.delegations) {
            if (delegation.taskId.equals(taskId) && !delegation.handled) {
                delegation.handled = true;
                delegation.cancelled = true;
            }
        }
    }

    private void cancelDelegationForProxy(InstanceState state, TaskId taskId, String proxy) {
        for (Delegation delegation : state.delegations) {
            if (delegation.taskId.equals(taskId) && !delegation.handled
                    && delegation.proxyParticipant.equals(proxy)) {
                delegation.handled = true;
                delegation.cancelled = true;
            }
        }
    }

    private void discardAfterPending(InstanceState state, TaskId taskId, String reason) {
        List<AfterPending> matches = state.afterPending.stream()
                .filter(p -> p.originalTaskId().equals(taskId))
                .toList();
        if (matches.isEmpty()) {
            return;
        }
        state.afterPending.removeAll(matches);
        for (AfterPending pending : matches) {
            event(state, "TASK_AFTER_SPAWN_DISCARDED", null, taskId, pending.actionId(),
                    attrs("reason", reason));
        }
    }

    private void discardAllAfterPending(InstanceState state, String reason) {
        List<AfterPending> all = List.copyOf(state.afterPending);
        state.afterPending.clear();
        for (AfterPending pending : all) {
            event(state, "TASK_AFTER_SPAWN_DISCARDED", null, pending.originalTaskId(),
                    pending.actionId(), attrs("reason", reason));
        }
    }

    private HumanTaskSpec deriveSpec(HumanTaskSpec base, List<ParticipantSpec> assignees,
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

    private SignCompletion beforeSignCompletion(HumanTaskSpec spec,
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

    private SignCompletion afterSignCompletion(HumanTaskSpec spec,
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

    private static RejectCompletion clampRejects(RejectCompletion completion, int size) {
        if (completion instanceof AtLeastRejects rejects) {
            return new AtLeastRejects(Math.min(rejects.count(), size));
        }
        return completion;
    }

    private static int requiredCount(SignCompletion rule, int denominator) {
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

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private WorkflowDefinition resolveDefinition(WorkflowDefinitionId id,
                                                 WorkflowDefinitionVersion version) {
        if (version != null) {
            WorkflowDefinition definition = definitions.get(definitionKey(id, version));
            if (definition == null) {
                throw new WorkflowDefinitionNotFoundException(
                        "definition " + id + " version " + versionText(version) + " not found");
            }
            return definition;
        }
        return findDefinition(id, null).orElseThrow(() ->
                new WorkflowDefinitionNotFoundException("definition " + id + " not found"));
    }

    private static String definitionKey(WorkflowDefinitionId id,
                                        WorkflowDefinitionVersion version) {
        return id.namespace() + '\u0000' + id.name() + '\u0000'
                + version.major() + '.' + version.minor();
    }

    private static String versionText(WorkflowDefinitionVersion version) {
        return version.major() + "." + version.minor();
    }

    private InstanceState requireInstance(WorkflowInstanceId id) {
        InstanceState state = instances.get(id);
        if (state == null) {
            throw new WorkflowNotFoundException("instance " + id.value() + " not found");
        }
        return state;
    }

    private void requireLive(InstanceState state) {
        if (state.terminal()) {
            throw new WorkflowStateConflictException(
                    "instance " + state.id.value() + " is " + state.status);
        }
    }

    private HumanTask requireTask(InstanceState state, TaskId taskId) {
        HumanTask task = state.tasks.get(taskId);
        if (task == null) {
            throw new HumanTaskNotFoundException("task " + taskId.value() + " not found");
        }
        return task;
    }

    private HumanTask requirePendingTask(InstanceState state, TaskId taskId) {
        HumanTask task = requireTask(state, taskId);
        if (task.status() != HumanTaskStatus.PENDING) {
            throw new HumanTaskStateConflictException(
                    "task " + taskId.value() + " is " + task.status());
        }
        return task;
    }

    private HumanTaskSpec specOf(InstanceState state, HumanTask task) {
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

    private StepDefinition requireCurrentStep(InstanceState state) {
        StepDefinition step = requireStep(state, state.currentStep);
        if (step == null) {
            throw new WorkflowStateConflictException(
                    "instance " + state.id.value() + " has no current step");
        }
        return step;
    }

    private StepDefinition requireStep(InstanceState state, String stepName) {
        if (stepName == null) {
            return null;
        }
        return state.definition.steps().stream()
                .filter(s -> s.stepName().equals(stepName))
                .findFirst()
                .orElse(null);
    }

    private int stepIndex(InstanceState state, String stepName) {
        List<StepDefinition> steps = state.definition.steps();
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).stepName().equals(stepName)) {
                return i;
            }
        }
        return -1;
    }

    private boolean hasCompletedStepAfter(InstanceState state, int index) {
        List<StepDefinition> steps = state.definition.steps();
        for (int i = index + 1; i < steps.size(); i++) {
            if (state.completedSteps.contains(steps.get(i).stepName())) {
                return true;
            }
        }
        return false;
    }

    private String nextStepName(WorkflowDefinition definition, String stepName) {
        List<StepDefinition> steps = definition.steps();
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).stepName().equals(stepName)) {
                return i + 1 < steps.size() ? steps.get(i + 1).stepName() : null;
            }
        }
        return null;
    }

    private List<ParticipantSpec> resolveAssignees(InstanceState state, String stepName,
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

    private HumanTask withFormData(HumanTask task, Map<String, String> formData, Instant now) {
        Map<String, String> merged = new HashMap<>(task.formData());
        merged.putAll(formData);
        return new HumanTask(task.id(), task.instanceId(), task.stepName(), task.kind(),
                task.status(), task.assignments(), merged, task.predecessorTaskId(),
                task.successorTaskId(), task.spawnedByActionId(), task.createdAt(), now,
                task.completedAt(), task.dueAt(), task.metadata());
    }

    private HumanTask withDueAt(HumanTask task, Instant dueAt, Instant now) {
        return new HumanTask(task.id(), task.instanceId(), task.stepName(), task.kind(),
                task.status(), task.assignments(), task.formData(), task.predecessorTaskId(),
                task.successorTaskId(), task.spawnedByActionId(), task.createdAt(), now,
                task.completedAt(), dueAt, task.metadata());
    }

    private HumanTask withSuccessor(HumanTask task, TaskId successor, Instant now) {
        return new HumanTask(task.id(), task.instanceId(), task.stepName(), task.kind(),
                task.status(), task.assignments(), task.formData(), task.predecessorTaskId(),
                successor, task.spawnedByActionId(), task.createdAt(), now,
                task.completedAt(), task.dueAt(), task.metadata());
    }

    private WorkflowInstance snapshot(InstanceState state) {
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

    private void touch(InstanceState state) {
        state.updatedAt = now();
        state.version++;
    }

    private void event(InstanceState state, String eventType, String stepName,
                       TaskId taskId, String actionId, Map<String, String> attributes) {
        state.events.add(new WorkflowHistoryEvent(idGenerator.id(), eventType,
                stepName, taskId == null ? null : taskId.value(), actionId, now(),
                attributes));
    }

    private static int pendingIndex(HumanTask task, String participant) {
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

    private static int assignmentIndex(HumanTask task, AssignmentStatus status,
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

    private ProxyRef locateProxy(InstanceState state, HumanTask task, String assignee) {
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

    private static boolean hasPendingAssignment(HumanTask task, String participant) {
        return task.assignments().stream()
                .anyMatch(a -> a.status() == AssignmentStatus.PENDING
                        && a.participant().value().equals(participant));
    }

    private static boolean hasCompletedBy(HumanTask task, String completedBy) {
        return task.assignments().stream()
                .filter(a -> a.status() == AssignmentStatus.COMPLETED)
                .map(TaskAssignment::record)
                .anyMatch(r -> r.assignee().equals(completedBy)
                        || (r.onBehalfOf() != null && r.onBehalfOf().equals(completedBy)));
    }

    private static boolean hasCandidateGroup(HumanTask task, List<String> groups) {
        return task.assignments().stream()
                .filter(a -> a.status() == AssignmentStatus.PENDING)
                .map(TaskAssignment::participant)
                .anyMatch(p -> (p.type() == ParticipantType.GROUP
                        || p.type() == ParticipantType.DEPARTMENT)
                        && groups.contains(p.value()));
    }

    private static Map<String, String> attrs(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(keyValues[i], nullSafe(keyValues[i + 1]));
        }
        return map;
    }

    private static String participantText(List<ParticipantSpec> participants) {
        return participants.stream()
                .map(p -> p.type().name() + ":" + p.value())
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static int cursorOffset(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor));
        } catch (NumberFormatException e) {
            throw new WorkflowProviderException("invalid cursor: " + cursor, e);
        }
    }

    private void requireCapability(boolean supported, String capability) {
        if (!supported) {
            throw new WorkflowStateConflictException(
                    "provider does not support " + capability);
        }
    }

    private void schedule(Runnable runnable, Instant at) {
        if (at == null || closed.get()) {
            return;
        }
        long delayMillis = Math.max(0,
                Duration.between(clock.instant(), at).toMillis());
        try {
            scheduler.schedule(runnable, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Provider 已关闭；状态已持久化，由调用方决定是否重启。
        }
    }

    private void scheduleTaskDue(InstanceState state, HumanTask task) {
        if (task.dueAt() == null) {
            return;
        }
        Instant dueAt = task.dueAt();
        schedule(() -> onTaskDue(state.id, task.id(), dueAt), dueAt);
    }

    private void scheduleStepTimeout(InstanceState state, StepDefinition step,
                                     Instant startedAt, Duration timeout) {
        if (timeout == null) {
            return;
        }
        Instant deadline = startedAt.plus(timeout);
        schedule(() -> onStepTimeout(state, step.stepName(), startedAt), deadline);
    }

    private Duration stepTimeout(InstanceState state, StepDefinition step) {
        return step.timeout() != null ? step.timeout() : state.definition.defaultStepTimeout();
    }

    private void scheduleWorkflowTimeout(InstanceState state) {
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

    private record ProxyRef(int index, Delegation delegation) {
    }
}
