package cn.byteforce.seedj.workflow.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 模型外壳：标识、请求、能力声明、查询与 Provider 默认实现。 */
class WorkflowModelDefaultsTest {

    private static final Instant NOW = Instant.parse("2026-02-01T08:00:00Z");
    private static final WorkflowDefinitionId DEFINITION =
            new WorkflowDefinitionId("hr", "leave-approval");
    private static final WorkflowDefinitionVersion VERSION = new WorkflowDefinitionVersion(1, 2);
    private static final WorkflowInstanceId INSTANCE = new WorkflowInstanceId("i-1");

    @Test
    @DisplayName("标识：不允许 null 与空白")
    void identifiers() {
        assertThat(new WorkflowInstanceId("i-1").value()).isEqualTo("i-1");
        assertThatThrownBy(() -> new WorkflowInstanceId(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WorkflowInstanceId(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");

        assertThat(new TaskId("t-1").value()).isEqualTo("t-1");
        assertThatThrownBy(() -> new TaskId(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TaskId("")).isInstanceOf(IllegalArgumentException.class);

        WorkflowDefinitionId definition = new WorkflowDefinitionId("hr", "leave-approval");
        assertThat(definition.namespace()).isEqualTo("hr");
        assertThat(definition.name()).isEqualTo("leave-approval");
        assertThatThrownBy(() -> new WorkflowDefinitionId(null, "n"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WorkflowDefinitionId("ns", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("版本号不允许负数")
    void definitionVersion() {
        assertThat(new WorkflowDefinitionVersion(2, 3).major()).isEqualTo(2);
        assertThat(new WorkflowDefinitionVersion(0, 0).minor()).isEqualTo(0);
        assertThatThrownBy(() -> new WorkflowDefinitionVersion(-1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
        assertThatThrownBy(() -> new WorkflowDefinitionVersion(0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("RetryPolicy：退避与倍数约束，noRetry 单次尝试")
    void retryPolicy() {
        RetryPolicy policy = new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(10), 2.0);
        assertThat(policy.maxAttempts()).isEqualTo(3);
        assertThat(policy.initialBackoff()).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.maxBackoff()).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.multiplier()).isEqualTo(2.0);

        assertThat(RetryPolicy.noRetry().maxAttempts()).isEqualTo(1);
        assertThat(RetryPolicy.noRetry().multiplier()).isEqualTo(1.0);

        assertThatThrownBy(
                        () -> new RetryPolicy(0, Duration.ofSeconds(1), Duration.ofSeconds(1), 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
        assertThatThrownBy(() -> new RetryPolicy(1, Duration.ZERO, Duration.ofSeconds(1), 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initialBackoff");
        assertThatThrownBy(() -> new RetryPolicy(1, null, Duration.ofSeconds(1), 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new RetryPolicy(1, Duration.ofSeconds(5), Duration.ofSeconds(4), 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBackoff");
        assertThatThrownBy(
                        () -> new RetryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(1), 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multiplier");
    }

    @Test
    @DisplayName("TaskQuery：limit 为正，pendingFor/completedBy 互斥")
    void taskQuery() {
        TaskQuery query =
                new TaskQuery(
                        INSTANCE,
                        "u-1",
                        null,
                        null,
                        "review",
                        HumanTaskStatus.PENDING,
                        NOW,
                        20,
                        null);

        assertThat(query.instanceId()).isEqualTo(INSTANCE);
        assertThat(query.pendingFor()).isEqualTo("u-1");
        assertThat(query.completedBy()).isNull();
        assertThat(query.candidateGroups()).isEmpty();
        assertThat(query.stepName()).isEqualTo("review");
        assertThat(query.status()).isEqualTo(HumanTaskStatus.PENDING);
        assertThat(query.dueBefore()).isEqualTo(NOW);
        assertThat(query.limit()).isEqualTo(20);
        assertThat(query.cursor()).isNull();

        assertThatThrownBy(() -> new TaskQuery(null, null, null, null, null, null, null, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(
                        () -> new TaskQuery(null, "u-1", "u-2", null, null, null, null, 10, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mutually exclusive");
    }

    @Test
    @DisplayName("WorkflowSearchQuery：limit 必须为正")
    void searchQuery() {
        WorkflowSearchQuery query =
                new WorkflowSearchQuery(
                        DEFINITION, WorkflowStatus.RUNNING, NOW, NOW, 50, "cursor-1");

        assertThat(query.definitionId()).isEqualTo(DEFINITION);
        assertThat(query.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(query.startedAfter()).isEqualTo(NOW);
        assertThat(query.startedBefore()).isEqualTo(NOW);
        assertThat(query.limit()).isEqualTo(50);
        assertThat(query.cursor()).isEqualTo("cursor-1");

        assertThatThrownBy(() -> new WorkflowSearchQuery(null, null, null, null, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    @DisplayName("StartWorkflowRequest：变量归一化为空 map")
    void startWorkflowRequest() {
        StartWorkflowRequest request =
                new StartWorkflowRequest(INSTANCE, DEFINITION, null, "u-1", null);

        assertThat(request.instanceId()).isEqualTo(INSTANCE);
        assertThat(request.definitionId()).isEqualTo(DEFINITION);
        assertThat(request.definitionVersion()).isNull();
        assertThat(request.startedBy()).isEqualTo("u-1");
        assertThat(request.initialVariables()).isEmpty();

        assertThatThrownBy(() -> new StartWorkflowRequest(null, DEFINITION, null, "u-1", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StartWorkflowRequest(INSTANCE, null, null, "u-1", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StartWorkflowRequest(INSTANCE, DEFINITION, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("取消与终止请求：实例必填，终止原因必填")
    void cancelAndTerminateRequests() {
        CancelWorkflowRequest cancel =
                new CancelWorkflowRequest(INSTANCE, "no longer needed", true);
        assertThat(cancel.instanceId()).isEqualTo(INSTANCE);
        assertThat(cancel.reason()).isEqualTo("no longer needed");
        assertThat(cancel.compensate()).isTrue();
        assertThat(new CancelWorkflowRequest(INSTANCE, null, false).compensate()).isFalse();
        assertThatThrownBy(() -> new CancelWorkflowRequest(null, null, false))
                .isInstanceOf(NullPointerException.class);

        TerminateWorkflowRequest terminate = new TerminateWorkflowRequest(INSTANCE, "fatal");
        assertThat(terminate.instanceId()).isEqualTo(INSTANCE);
        assertThat(terminate.reason()).isEqualTo("fatal");
        assertThatThrownBy(() -> new TerminateWorkflowRequest(INSTANCE, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TerminateWorkflowRequest(null, "fatal"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("WorkflowFailure / WorkflowHistoryEvent：details 与 attributes 归一化")
    void failureAndHistoryEvent() {
        WorkflowFailure failure =
                new WorkflowFailure("timeout", "step timed out", true, "review", null);
        assertThat(failure.errorType()).isEqualTo("timeout");
        assertThat(failure.errorMessage()).isEqualTo("step timed out");
        assertThat(failure.retryable()).isTrue();
        assertThat(failure.failedStep()).isEqualTo("review");
        assertThat(failure.details()).isEmpty();
        assertThatThrownBy(() -> new WorkflowFailure(null, null, false, null, null))
                .isInstanceOf(NullPointerException.class);

        WorkflowHistoryEvent event =
                new WorkflowHistoryEvent("e-1", "TASK_CREATED", "review", "t-1", null, NOW, null);
        assertThat(event.eventId()).isEqualTo("e-1");
        assertThat(event.eventType()).isEqualTo("TASK_CREATED");
        assertThat(event.stepName()).isEqualTo("review");
        assertThat(event.taskId()).isEqualTo("t-1");
        assertThat(event.actionId()).isNull();
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.attributes()).isEmpty();
    }

    @Test
    @DisplayName("WorkflowDefinition / WorkflowInstance：集合归一化，core 字段必填")
    void definitionAndInstance() {
        StepDefinition step =
                new StepDefinition(
                        "review",
                        StepKind.ACTIVITY,
                        Duration.ofMinutes(5),
                        RetryPolicy.noRetry(),
                        null,
                        null);
        WorkflowDefinition definition =
                new WorkflowDefinition(
                        DEFINITION,
                        VERSION,
                        List.of(step),
                        Duration.ofMinutes(5),
                        Duration.ofDays(1),
                        null);

        assertThat(definition.id()).isEqualTo(DEFINITION);
        assertThat(definition.version()).isEqualTo(VERSION);
        assertThat(definition.steps()).containsExactly(step);
        assertThat(definition.metadata()).isEmpty();

        assertThatThrownBy(() -> new WorkflowDefinition(null, VERSION, null, null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WorkflowDefinition(DEFINITION, null, null, null, null, null))
                .isInstanceOf(NullPointerException.class);

        WorkflowInstance instance =
                new WorkflowInstance(
                        INSTANCE,
                        DEFINITION,
                        VERSION,
                        WorkflowStatus.RUNNING,
                        "review",
                        null,
                        "u-1",
                        NOW,
                        NOW,
                        null,
                        null,
                        null);

        assertThat(instance.id()).isEqualTo(INSTANCE);
        assertThat(instance.definitionId()).isEqualTo(DEFINITION);
        assertThat(instance.definitionVersion()).isEqualTo(VERSION);
        assertThat(instance.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(instance.currentStep()).isEqualTo("review");
        assertThat(instance.pendingTaskIds()).isEmpty();
        assertThat(instance.startedBy()).isEqualTo("u-1");
        assertThat(instance.variables()).isEmpty();
        assertThat(instance.failure()).isNull();
        assertThat(instance.completedAt()).isNull();

        assertThatThrownBy(
                        () ->
                                new WorkflowInstance(
                                        null,
                                        DEFINITION,
                                        VERSION,
                                        WorkflowStatus.RUNNING,
                                        null,
                                        null,
                                        "u-1",
                                        NOW,
                                        NOW,
                                        null,
                                        null,
                                        null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new WorkflowInstance(
                                        INSTANCE,
                                        null,
                                        VERSION,
                                        WorkflowStatus.RUNNING,
                                        null,
                                        null,
                                        "u-1",
                                        NOW,
                                        NOW,
                                        null,
                                        null,
                                        null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new WorkflowInstance(
                                        INSTANCE,
                                        DEFINITION,
                                        null,
                                        WorkflowStatus.RUNNING,
                                        null,
                                        null,
                                        "u-1",
                                        NOW,
                                        NOW,
                                        null,
                                        null,
                                        null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new WorkflowInstance(
                                        INSTANCE,
                                        DEFINITION,
                                        VERSION,
                                        null,
                                        null,
                                        null,
                                        "u-1",
                                        NOW,
                                        NOW,
                                        null,
                                        null,
                                        null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("ParticipantResolutionContext：固定字段全部必填，variables 归一化")
    void participantResolutionContext() {
        ParticipantResolutionContext context =
                new ParticipantResolutionContext(
                        "users-by-role", INSTANCE, DEFINITION, VERSION, "review", null, NOW);

        assertThat(context.expression()).isEqualTo("users-by-role");
        assertThat(context.instanceId()).isEqualTo(INSTANCE);
        assertThat(context.definitionId()).isEqualTo(DEFINITION);
        assertThat(context.definitionVersion()).isEqualTo(VERSION);
        assertThat(context.stepName()).isEqualTo("review");
        assertThat(context.variables()).isEmpty();
        assertThat(context.resolvedAt()).isEqualTo(NOW);

        assertThatThrownBy(
                        () ->
                                new ParticipantResolutionContext(
                                        null, INSTANCE, DEFINITION, VERSION, "review", null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new ParticipantResolutionContext(
                                        "e", null, DEFINITION, VERSION, "review", null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new ParticipantResolutionContext(
                                        "e", INSTANCE, null, VERSION, "review", null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new ParticipantResolutionContext(
                                        "e", INSTANCE, DEFINITION, null, "review", null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new ParticipantResolutionContext(
                                        "e", INSTANCE, DEFINITION, VERSION, null, null, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("StepDefinition：WAIT_SIGNAL 必须声明信号名，其它类型不得声明")
    void stepDefinitionWaitSignal() {
        StepDefinition wait =
                StepDefinition.waitSignal(
                        "wait-payment", "payment-received", Duration.ofHours(1), List.of("review"));

        assertThat(wait.stepName()).isEqualTo("wait-payment");
        assertThat(wait.kind()).isEqualTo(StepKind.WAIT_SIGNAL);
        assertThat(wait.waitSignalName()).isEqualTo("payment-received");
        assertThat(wait.dependsOn()).containsExactly("review");
        assertThat(wait.retryPolicy()).isEqualTo(RetryPolicy.noRetry());
        assertThat(wait.humanTask()).isNull();

        assertThatThrownBy(
                        () ->
                                new StepDefinition(
                                        "wait",
                                        StepKind.WAIT_SIGNAL,
                                        Duration.ofHours(1),
                                        RetryPolicy.noRetry(),
                                        List.of(),
                                        Map.of(),
                                        null,
                                        " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("waitSignalName");
        assertThatThrownBy(
                        () ->
                                new StepDefinition(
                                        "wait",
                                        StepKind.WAIT_SIGNAL,
                                        Duration.ofHours(1),
                                        RetryPolicy.noRetry(),
                                        List.of(),
                                        Map.of(),
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new StepDefinition(
                                        "act",
                                        StepKind.ACTIVITY,
                                        Duration.ofHours(1),
                                        RetryPolicy.noRetry(),
                                        List.of(),
                                        Map.of(),
                                        null,
                                        "payment-received"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("waitSignalName");
        assertThatThrownBy(
                        () ->
                                new StepDefinition(
                                        null,
                                        StepKind.ACTIVITY,
                                        null,
                                        RetryPolicy.noRetry(),
                                        null,
                                        null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new StepDefinition(
                                        "act", null, null, RetryPolicy.noRetry(), null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("StepDefinition：无 timeout 时 dueIn 不参与比较")
    void stepDefinitionAllowsDueInWithoutTimeout() {
        HumanTaskSpec spec =
                new HumanTaskSpec(
                        List.of(new ParticipantSpec(ParticipantType.USER, "u-1", Map.of())),
                        new AllApprove(),
                        null,
                        null,
                        null,
                        null,
                        false,
                        null,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        Duration.ofDays(2),
                        null);

        StepDefinition step =
                new StepDefinition(
                        "review",
                        StepKind.HUMAN_TASK,
                        null,
                        RetryPolicy.noRetry(),
                        null,
                        null,
                        spec,
                        null);

        assertThat(step.humanTask()).isSameAs(spec);
        assertThat(step.dependsOn()).isEmpty();
        assertThat(step.metadata()).isEmpty();
    }

    @Test
    @DisplayName("WorkflowCapabilities：三档预设与 21 项声明")
    void capabilities() {
        WorkflowCapabilities basic = WorkflowCapabilities.basic();
        assertThat(basic.supportsVersioning()).isTrue();
        assertThat(basic.supportsHumanTasks()).isFalse();
        assertThat(basic.supportsCompensation()).isFalse();

        WorkflowCapabilities humanTasks = WorkflowCapabilities.humanTasks();
        assertThat(humanTasks.supportsHumanTasks()).isTrue();
        assertThat(humanTasks.supportsSearch()).isTrue();
        assertThat(humanTasks.supportsRejectToStep()).isFalse();
        assertThat(humanTasks.supportsAddAssignee()).isFalse();

        WorkflowCapabilities approval = WorkflowCapabilities.approval();
        assertThat(approval.supportsCompensation()).isTrue();
        assertThat(approval.supportsRejectToStep()).isTrue();
        assertThat(approval.supportsWithdrawInstance()).isTrue();
        assertThat(approval.supportsCc()).isTrue();
        assertThat(approval.supportsDynamicForm()).isTrue();
        assertThat(approval.supportsProviderClock()).isTrue();
        assertThat(approval.supportsResumeWorkflow()).isTrue();
    }

    @Test
    @DisplayName("完成规则：AtLeastCount / AtLeastRejects 必须为正")
    void completionRules() {
        assertThat(new AnyApprove()).isNotNull();
        assertThat(new RecordAndContinue()).isNotNull();
        assertThat(new AnyRejectTerminates()).isNotNull();
        assertThat(new AtLeastCount(1).count()).isEqualTo(1);
        assertThat(new AtLeastRejects(2).count()).isEqualTo(2);
        assertThat(new AtLeastPercentage(0.5).percentage()).isEqualTo(0.5);

        assertThatThrownBy(() -> new AtLeastCount(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("count");
        assertThatThrownBy(() -> new AtLeastRejects(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("count");
    }

    @Test
    @DisplayName("异常层次：全部继承 WorkflowException 并保留 cause")
    void exceptionHierarchy() {
        RuntimeException cause = new RuntimeException("boom");

        assertThat(new WorkflowException("m")).hasMessage("m").hasNoCause();
        assertThat(new WorkflowException("m", cause)).hasCause(cause);
        assertThat(new WorkflowProviderException("m", cause)).hasCause(cause);
        assertThat(new WorkflowStateConflictException("m", cause)).hasCause(cause);
        assertThat(new ParticipantResolutionException("m", cause)).hasCause(cause);

        assertThat(
                        List.of(
                                new WorkflowTimeoutException("m"),
                                new WorkflowNotFoundException("m"),
                                new WorkflowDefinitionNotFoundException("m"),
                                new AssigneeNotAllowedException("m"),
                                new HumanTaskNotFoundException("m"),
                                new HumanTaskStateConflictException("m"),
                                new RejectTargetNotAllowedException("m"),
                                new WithdrawNotAllowedException("m"),
                                new WorkflowVersionConflictException("m"),
                                new WorkflowProviderException("m"),
                                new WorkflowStateConflictException("m"),
                                new ParticipantResolutionException("m")))
                .allSatisfy(
                        error ->
                                assertThat(error)
                                        .isInstanceOf(WorkflowException.class)
                                        .hasMessage("m"));
    }

    @Test
    @DisplayName("WorkflowProvider：默认能力、默认时钟与未实现能力")
    void providerDefaults() {
        WorkflowProvider provider = new MinimalProvider();

        assertThat(provider.capabilities().supportsHumanTasks()).isFalse();
        assertThat(provider.now()).isBeforeOrEqualTo(Instant.now());
        assertThatThrownBy(() -> provider.resolveParticipants(null))
                .isInstanceOf(WorkflowProviderException.class)
                .hasMessageContaining("participant resolution");
        provider.close();
    }

    /** 只实现抽象方法的最小 Provider；默认方法由契约提供。 */
    private static final class MinimalProvider implements WorkflowProvider {

        @Override
        public void register(WorkflowDefinition definition) {}

        @Override
        public Optional<WorkflowDefinition> findDefinition(
                WorkflowDefinitionId id, WorkflowDefinitionVersion version) {
            return Optional.empty();
        }

        @Override
        public WorkflowInstance start(StartWorkflowRequest request) {
            return null;
        }

        @Override
        public WorkflowInstance act(ActionRequest request) {
            return null;
        }

        @Override
        public WorkflowInstance cancel(CancelWorkflowRequest request) {
            return null;
        }

        @Override
        public WorkflowInstance terminate(TerminateWorkflowRequest request) {
            return null;
        }

        @Override
        public Optional<WorkflowInstance> describe(WorkflowInstanceId instanceId) {
            return Optional.empty();
        }

        @Override
        public List<WorkflowInstance> search(WorkflowSearchQuery query) {
            return List.of();
        }

        @Override
        public List<WorkflowHistoryEvent> history(WorkflowInstanceId instanceId) {
            return List.of();
        }

        @Override
        public List<HumanTask> listTasks(TaskQuery query) {
            return List.of();
        }

        @Override
        public Optional<HumanTask> describeTask(TaskId taskId) {
            return Optional.empty();
        }

        @Override
        public void close() {}
    }

    @Test
    @DisplayName("WorkflowStatus / AssignmentStatus / StepKind 取值稳定")
    void enumValues() {
        assertThat(WorkflowStatus.values())
                .containsExactly(
                        WorkflowStatus.PENDING,
                        WorkflowStatus.RUNNING,
                        WorkflowStatus.WAITING,
                        WorkflowStatus.COMPENSATING,
                        WorkflowStatus.COMPLETED,
                        WorkflowStatus.FAILED,
                        WorkflowStatus.CANCELLED,
                        WorkflowStatus.TERMINATED);
        assertThat(AssignmentStatus.values())
                .containsExactly(
                        AssignmentStatus.PENDING,
                        AssignmentStatus.COMPLETED,
                        AssignmentStatus.SUSPENDED,
                        AssignmentStatus.CANCELLED,
                        AssignmentStatus.TRANSFERRED,
                        AssignmentStatus.DELEGATED);
        assertThat(StepKind.values())
                .containsExactly(
                        StepKind.ACTIVITY,
                        StepKind.HUMAN_TASK,
                        StepKind.WAIT_SIGNAL,
                        StepKind.COMPENSATION);
        assertThat(ParticipantType.values())
                .containsExactly(
                        ParticipantType.USER,
                        ParticipantType.ROLE,
                        ParticipantType.GROUP,
                        ParticipantType.DEPARTMENT,
                        ParticipantType.EXPRESSION);
        assertThat(HumanTaskStatus.valueOf("WITHDRAWN")).isEqualTo(HumanTaskStatus.WITHDRAWN);
    }

    @Test
    @DisplayName("HumanTaskSpec：拒绝目标与表单 schema 归一化为空集合")
    void humanTaskSpecDefaults() {
        HumanTaskSpec spec =
                new HumanTaskSpec(
                        List.of(new ParticipantSpec(ParticipantType.USER, "u-1", Map.of())),
                        new AnyApprove(),
                        null,
                        null,
                        "leave-form",
                        null,
                        true,
                        Set.of(AddAssigneePosition.AFTER),
                        true,
                        true,
                        false,
                        true,
                        true,
                        true,
                        null,
                        null);

        assertThat(spec.rejectCompletion()).isInstanceOf(AnyRejectTerminates.class);
        assertThat(spec.rejectTargets()).isEmpty();
        assertThat(spec.formSchema()).isEmpty();
        assertThat(spec.metadata()).isEmpty();
        assertThat(spec.allowedAddPositions()).containsExactly(AddAssigneePosition.AFTER);
        assertThat(spec.formKey()).isEqualTo("leave-form");
    }

    @Test
    @DisplayName("HumanTaskSpec：签名/驳回计数不得超过参与者数量")
    void humanTaskSpecRejectCount() {
        List<ParticipantSpec> assignees =
                List.of(new ParticipantSpec(ParticipantType.USER, "u-1", Map.of()));

        assertThatThrownBy(
                        () ->
                                new HumanTaskSpec(
                                        assignees,
                                        new AllApprove(),
                                        new AtLeastRejects(2),
                                        null,
                                        null,
                                        null,
                                        false,
                                        null,
                                        false,
                                        false,
                                        false,
                                        false,
                                        false,
                                        false,
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AtLeastRejects");
        assertThatThrownBy(
                        () ->
                                new HumanTaskSpec(
                                        List.of(),
                                        new AllApprove(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        false,
                                        null,
                                        false,
                                        false,
                                        false,
                                        false,
                                        false,
                                        false,
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assignees");
        assertThatThrownBy(
                        () ->
                                new HumanTaskSpec(
                                        assignees,
                                        new AllApprove(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        false,
                                        null,
                                        false,
                                        false,
                                        false,
                                        false,
                                        false,
                                        false,
                                        Duration.ZERO,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dueIn");
        assertThatThrownBy(
                        () ->
                                new HumanTaskSpec(
                                        null, null, null, null, null, null, false, null, false,
                                        false, false, false, false, false, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("signCompletion");
    }
}
