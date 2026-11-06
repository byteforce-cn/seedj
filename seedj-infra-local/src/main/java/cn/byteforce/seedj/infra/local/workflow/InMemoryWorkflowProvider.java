package cn.byteforce.seedj.infra.local.workflow;

import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.workflow.spi.ActionRequest;
import cn.byteforce.seedj.workflow.spi.AddAssigneeAction;
import cn.byteforce.seedj.workflow.spi.ApproveAction;
import cn.byteforce.seedj.workflow.spi.CancelWorkflowRequest;
import cn.byteforce.seedj.workflow.spi.CcAction;
import cn.byteforce.seedj.workflow.spi.DelegateAction;
import cn.byteforce.seedj.workflow.spi.HumanTaskStateConflictException;
import cn.byteforce.seedj.workflow.spi.RemoveAssigneeAction;
import cn.byteforce.seedj.workflow.spi.RejectAction;
import cn.byteforce.seedj.workflow.spi.ResumeWorkflowAction;
import cn.byteforce.seedj.workflow.spi.SignalAction;
import cn.byteforce.seedj.workflow.spi.StartWorkflowRequest;
import cn.byteforce.seedj.workflow.spi.TerminateWorkflowRequest;
import cn.byteforce.seedj.workflow.spi.TransferAction;
import cn.byteforce.seedj.workflow.spi.UrgeAction;
import cn.byteforce.seedj.workflow.spi.WorkflowAction;
import cn.byteforce.seedj.workflow.spi.WorkflowCapabilities;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinition;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionId;
import cn.byteforce.seedj.workflow.spi.WorkflowInstance;
import cn.byteforce.seedj.workflow.spi.WorkflowInstanceId;
import cn.byteforce.seedj.workflow.spi.WorkflowProvider;
import cn.byteforce.seedj.workflow.spi.WorkflowStateConflictException;
import cn.byteforce.seedj.workflow.spi.WorkflowStatus;
import cn.byteforce.seedj.workflow.spi.WorkflowVersionConflictException;
import cn.byteforce.seedj.workflow.spi.WithdrawAction;
import cn.byteforce.seedj.workflow.spi.WithdrawInstanceAction;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

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
 *
 * <p>实现按职责拆为定义存储、实例引擎、任务生命周期、动作处理、超时调度与查询六个协作者；
 * 本类只做入口校验与委托。</p>
 */
public final class InMemoryWorkflowProvider implements WorkflowProvider {

    private final InMemoryWorkflowSupport support;
    private final InMemoryWorkflowDefinitionStore definitions;
    private final InMemoryWorkflowTaskLifecycle lifecycle;
    private final InMemoryWorkflowInstanceEngine engine;
    private final InMemoryWorkflowTimeouts timeouts;
    private final InMemoryWorkflowActions actions;
    private final InMemoryWorkflowQueryService queries;

    /** 默认构造：系统时钟、自有调度线程、进程内 ID。 */
    public InMemoryWorkflowProvider() {
        this(Clock.systemUTC(), newScheduler(), true, localIdGenerator());
    }

    /**
     * 指定时钟。
     *
     * @param clock 取时入口
     */
    public InMemoryWorkflowProvider(Clock clock) {
        this(clock, newScheduler(), true, localIdGenerator());
    }

    /**
     * 指定时钟与 ID 生成器。
     *
     * @param clock       取时入口
     * @param idGenerator ID 生成器
     */
    public InMemoryWorkflowProvider(Clock clock, IdGenerator<String> idGenerator) {
        this(clock, newScheduler(), true, idGenerator == null ? localIdGenerator() : idGenerator);
    }

    /**
     * 指定时钟与调度器；调度器生命周期由调用方管理。
     *
     * @param clock     取时入口
     * @param scheduler 调度器
     */
    public InMemoryWorkflowProvider(Clock clock, ScheduledExecutorService scheduler) {
        this(clock, scheduler, false, localIdGenerator());
    }

    private InMemoryWorkflowProvider(Clock clock, ScheduledExecutorService scheduler,
                                     boolean ownsScheduler, IdGenerator<String> idGenerator) {
        this.support = new InMemoryWorkflowSupport(clock, scheduler, ownsScheduler, idGenerator);
        this.definitions = new InMemoryWorkflowDefinitionStore(support);
        this.lifecycle = new InMemoryWorkflowTaskLifecycle(support);
        this.engine = new InMemoryWorkflowInstanceEngine(support, definitions, lifecycle);
        this.timeouts = new InMemoryWorkflowTimeouts(support, engine, lifecycle);
        this.engine.bindTimeouts(timeouts);
        this.actions = new InMemoryWorkflowActions(support, engine, lifecycle, timeouts);
        this.queries = new InMemoryWorkflowQueryService(support);
    }

    private static IdGenerator<String> localIdGenerator() {
        String prefix = "wf-" + ProcessHandle.current().pid() + "-"
                + Long.toHexString(System.nanoTime());
        AtomicLong sequence = new AtomicLong();
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

    @Override
    public void register(WorkflowDefinition definition) {
        definitions.register(definition);
    }

    @Override
    public Optional<WorkflowDefinition> findDefinition(WorkflowDefinitionId id,
                                                        cn.byteforce.seedj.workflow.spi
                                                                .WorkflowDefinitionVersion version) {
        return definitions.findDefinition(id, version);
    }

    @Override
    public WorkflowInstance start(StartWorkflowRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        synchronized (support.instances) {
            InstanceState existing = support.instances.get(request.instanceId());
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
                    return support.snapshot(existing);
                } finally {
                    existing.lock.unlock();
                }
            }

            WorkflowDefinition definition = definitions.resolveDefinition(
                    request.definitionId(), request.definitionVersion());
            Instant now = now();
            InstanceState state = new InstanceState(request.instanceId(), definition,
                    request.startedBy(), now, now);
            state.variables.putAll(request.initialVariables());
            state.updatedAt = now;
            support.instances.put(state.id, state);

            state.lock.lock();
            try {
                support.event(state, "INSTANCE_STARTED", null, null, null,
                        InMemoryWorkflowSupport.attrs("definitionId", definition.id().name(),
                                "version", definitions.versionText(definition.version()),
                                "startedBy", request.startedBy()));
                if (definition.steps().isEmpty()) {
                    throw new WorkflowStateConflictException("definition has no steps");
                }
                engine.enterStep(state, definition.steps().get(0).stepName(), null);
                timeouts.scheduleWorkflowTimeout(state);
                support.touch(state);
                InMemoryWorkflowSupport.LOG.info("workflow started instanceId={} definitionId={} version={} step={}",
                        state.id.value(), definition.id(),
                        definitions.versionText(definition.version()), state.currentStep);
                return support.snapshot(state);
            } catch (RuntimeException failure) {
                support.instances.remove(state.id, state);
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
        synchronized (support.actionGate) {
            WorkflowInstanceId prior = support.actionIndex.get(action.actionId());
            if (prior != null) {
                return support.snapshot(support.requireInstance(prior));
            }
            InstanceState state = support.requireInstance(request.instanceId());
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
                support.actionIndex.put(action.actionId(), state.id);
                support.touch(state);
                InMemoryWorkflowSupport.LOG.debug("workflow action instanceId={} definitionId={} actionId={} type={} "
                                + "step={}",
                        state.id.value(), state.definition.id(), action.actionId(),
                        action.getClass().getSimpleName(), state.currentStep);
                return support.snapshot(state);
            } finally {
                state.lock.unlock();
            }
        }
    }

    private void dispatch(InstanceState state, WorkflowAction action) {
        if (action instanceof SignalAction signal) {
            actions.onSignal(state, signal);
        } else if (action instanceof ApproveAction approve) {
            actions.onApprove(state, approve);
        } else if (action instanceof RejectAction reject) {
            actions.onReject(state, reject);
        } else if (action instanceof AddAssigneeAction add) {
            actions.onAddAssignee(state, add);
        } else if (action instanceof RemoveAssigneeAction remove) {
            actions.onRemoveAssignee(state, remove);
        } else if (action instanceof TransferAction transfer) {
            actions.onTransfer(state, transfer);
        } else if (action instanceof DelegateAction delegate) {
            actions.onDelegate(state, delegate);
        } else if (action instanceof WithdrawAction withdraw) {
            actions.onWithdraw(state, withdraw);
        } else if (action instanceof WithdrawInstanceAction withdraw) {
            actions.onWithdrawInstance(state, withdraw);
        } else if (action instanceof UrgeAction urge) {
            actions.onUrge(state, urge);
        } else if (action instanceof CcAction cc) {
            actions.onCc(state, cc);
        } else if (action instanceof ResumeWorkflowAction resume) {
            actions.onResume(state, resume);
        } else {
            throw new WorkflowStateConflictException(
                    "unsupported action: " + action.getClass().getName());
        }
    }

    @Override
    public WorkflowInstance cancel(CancelWorkflowRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        InstanceState state = support.requireInstance(request.instanceId());
        state.lock.lock();
        try {
            support.requireLive(state);
            if (request.compensate()) {
                state.status = WorkflowStatus.COMPENSATING;
            }
            lifecycle.cancelAllActiveTasks(state, "INSTANCE_CANCELLED");
            lifecycle.discardAllAfterPending(state, "UPSTREAM_ROLLBACK");
            state.status = WorkflowStatus.CANCELLED;
            state.completedAt = now();
            support.event(state, "INSTANCE_CANCELLED", state.currentStep, null, null,
                    InMemoryWorkflowSupport.attrs("reason", InMemoryWorkflowSupport.nullSafe(request.reason()),
                            "compensate", Boolean.toString(request.compensate())));
            support.touch(state);
            return support.snapshot(state);
        } finally {
            state.lock.unlock();
        }
    }

    @Override
    public WorkflowInstance terminate(TerminateWorkflowRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        InstanceState state = support.requireInstance(request.instanceId());
        state.lock.lock();
        try {
            support.requireLive(state);
            lifecycle.cancelAllActiveTasks(state, "TERMINATED");
            lifecycle.discardAllAfterPending(state, "UPSTREAM_ROLLBACK");
            state.status = WorkflowStatus.TERMINATED;
            state.completedAt = now();
            support.event(state, "INSTANCE_TERMINATED", state.currentStep, null, null,
                    InMemoryWorkflowSupport.attrs("reason", request.reason()));
            support.touch(state);
            return support.snapshot(state);
        } finally {
            state.lock.unlock();
        }
    }

    @Override
    public Optional<WorkflowInstance> describe(WorkflowInstanceId instanceId) {
        return queries.describe(instanceId);
    }

    @Override
    public java.util.List<WorkflowInstance> search(
            cn.byteforce.seedj.workflow.spi.WorkflowSearchQuery query) {
        return queries.search(query);
    }

    @Override
    public java.util.List<cn.byteforce.seedj.workflow.spi.WorkflowHistoryEvent> history(
            WorkflowInstanceId instanceId) {
        return queries.history(instanceId);
    }

    @Override
    public java.util.List<cn.byteforce.seedj.workflow.spi.HumanTask> listTasks(
            cn.byteforce.seedj.workflow.spi.TaskQuery query) {
        return queries.listTasks(query);
    }

    @Override
    public Optional<cn.byteforce.seedj.workflow.spi.HumanTask> describeTask(
            cn.byteforce.seedj.workflow.spi.TaskId taskId) {
        return queries.describeTask(taskId);
    }

    @Override
    public java.util.List<cn.byteforce.seedj.workflow.spi.ParticipantSpec> resolveParticipants(
            cn.byteforce.seedj.workflow.spi.ParticipantResolutionContext context) {
        return support.resolveParticipants(context);
    }

    @Override
    public Instant now() {
        return support.clock.instant();
    }

    @Override
    public WorkflowCapabilities capabilities() {
        return WorkflowCapabilities.approval();
    }

    @Override
    public void close() {
        support.close();
    }
}
