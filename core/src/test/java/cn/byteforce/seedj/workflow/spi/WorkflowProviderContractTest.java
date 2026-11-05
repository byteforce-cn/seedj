package cn.byteforce.seedj.workflow.spi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * v2.4 工作流 Provider 契约测试（TCK）。
 *
 * <p>实现模块只需 {@code extends WorkflowProviderContractTest} 并返回 Provider 实例。
 * 覆盖：幂等、会签、驳回回退、死锁、前/后加签、转办、委托、撤回、超时、恢复、催办、抄送、
 * WAIT_SIGNAL、待办一致性。时间相关用例使用短时长 + 轮询，最大等待 5 秒。
 */
public abstract class WorkflowProviderContractTest {

    protected WorkflowProvider provider;

    protected abstract WorkflowProvider createProvider();

    @BeforeEach
    void setUpProvider() {
        provider = createProvider();
    }

    @AfterEach
    void closeProvider() {
        provider.close();
    }

    // ------------------------------------------------------------------
    // 基础契约
    // ------------------------------------------------------------------

    @Test
    void startIsIdempotentAndCapabilitiesAreDeclared() {
        WorkflowDefinition definition = definition("idempotent",
                human("review", basicSpec("u1")));
        provider.register(definition);

        WorkflowInstanceId id = new WorkflowInstanceId("wf-" + UUID.randomUUID());
        StartWorkflowRequest request = new StartWorkflowRequest(id, definition.id(),
                definition.version(), "starter", Map.of("orderId", "o-1"));

        WorkflowInstance first = provider.start(request);
        WorkflowInstance second = provider.start(request);

        assertThat(second.startedAt()).isEqualTo(first.startedAt());
        assertThat(second.pendingTaskIds()).containsExactlyElementsOf(first.pendingTaskIds());
        assertThat(second.variables()).containsEntry("orderId", "o-1");
        assertThat(provider.describe(id)).isPresent();

        WorkflowCapabilities capabilities = provider.capabilities();
        assertThat(capabilities.supportsHumanTasks()).isTrue();
        assertThat(capabilities.supportsProviderClock()).isTrue();
        assertThat(capabilities.supportsResumeWorkflow()).isTrue();
        assertThat(provider.now()).isNotNull();
    }

    @Test
    void approveCompletesSingleApproverWorkflow() {
        WorkflowDefinition definition = definition("single",
                human("review", basicSpec("u1")), activity("done"));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");

        WorkflowInstance running = provider.describe(id).orElseThrow();
        assertThat(running.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(running.currentStep()).isEqualTo("review");
        assertThat(running.pendingTaskIds()).hasSize(1);

        HumanTask task = pendingTask(id);
        assertThat(task.kind()).isEqualTo(HumanTaskKind.ORIGINAL);
        assertThat(task.pendingAssignees()).extracting(ParticipantSpec::value)
                .containsExactly("u1");

        WorkflowInstance completed = approve(id, task.id(), "u1");

        assertThat(completed.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(completed.pendingTaskIds()).isEmpty();
        assertThat(provider.describeTask(task.id()).orElseThrow().status())
                .isEqualTo(HumanTaskStatus.COMPLETED);
        assertThat(eventTypes(id)).contains("TASK_COMPLETED", "INSTANCE_COMPLETED");
    }

    @Test
    void duplicateActionIdIsIdempotent() {
        WorkflowDefinition definition = definition("duplicate-action",
                human("review", basicSpec("u1")));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");
        HumanTask task = pendingTask(id);
        String actionId = UUID.randomUUID().toString();

        WorkflowInstance first = provider.act(new ActionRequest(id,
                new ApproveAction(actionId, task.id(), "u1", "ok", Map.of(), Instant.now())));
        WorkflowInstance second = provider.act(new ActionRequest(id,
                new ApproveAction(actionId, task.id(), "u1", "ok", Map.of(), Instant.now())));

        assertThat(first.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(second.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(provider.describeTask(task.id()).orElseThrow().approveRecords()).hasSize(1);
    }

    @Test
    void allApproveRequiresEveryParticipant() {
        WorkflowDefinition definition = definition("all-approve",
                human("review", basicSpec("u1", "u2")));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");
        TaskId taskId = pendingTask(id).id();

        WorkflowInstance afterFirst = approve(id, taskId, "u1");
        assertThat(afterFirst.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(afterFirst.pendingTaskIds()).hasSize(1);

        WorkflowInstance afterSecond = approve(id, taskId, "u2");
        assertThat(afterSecond.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(provider.describeTask(taskId).orElseThrow().status())
                .isEqualTo(HumanTaskStatus.COMPLETED);
    }

    @Test
    void rejectTerminatesTaskAndRollsBackToTargetStep() {
        WorkflowDefinition definition = definition("reject",
                activity("start"),
                human("review", spec(List.of("u1", "u2"), new AllApprove(),
                        new AnyRejectTerminates(), Set.of("start"), false, Set.of(),
                        false, false, false, false, false, false, null, Map.of())));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");
        TaskId firstTaskId = pendingTask(id).id();

        WorkflowInstance afterReject = provider.act(new ActionRequest(id,
                new RejectAction(UUID.randomUUID().toString(), firstTaskId, "u1",
                        "start", "not enough", Instant.now())));

        assertThat(afterReject.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(afterReject.currentStep()).isEqualTo("review");
        assertThat(afterReject.pendingTaskIds()).hasSize(1)
                .doesNotContain(firstTaskId);
        HumanTask rejected = provider.describeTask(firstTaskId).orElseThrow();
        assertThat(rejected.status()).isEqualTo(HumanTaskStatus.REJECTED);
        assertThat(rejected.assignments())
                .anySatisfy(a -> {
                    assertThat(a.status()).isEqualTo(AssignmentStatus.COMPLETED);
                    assertThat(a.record().decision()).isEqualTo(ActionDecision.REJECT);
                })
                .anySatisfy(a -> assertThat(a.status()).isEqualTo(AssignmentStatus.CANCELLED));
        assertThat(eventTypes(id)).contains("TASK_REJECTED", "INSTANCE_ROLLED_BACK");
        assertThat(eventTypes(id)).doesNotContain("TASK_AFTER_SPAWN_DISCARDED");
    }

    @Test
    void recordAndContinueDeadlockRollsBack() {
        WorkflowDefinition definition = definition("deadlock",
                human("review", spec(List.of("u1", "u2"), new AllApprove(),
                        new RecordAndContinue(), Set.of("review"), false, Set.of(),
                        false, false, false, false, false, false, null, Map.of())));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");
        TaskId taskId = pendingTask(id).id();

        provider.act(new ActionRequest(id, new RejectAction(UUID.randomUUID().toString(),
                taskId, "u1", "review", "no", Instant.now())));
        assertThat(provider.describe(id).orElseThrow().status())
                .isEqualTo(WorkflowStatus.RUNNING);

        WorkflowInstance afterSecondReject = provider.act(new ActionRequest(id,
                new RejectAction(UUID.randomUUID().toString(), taskId, "u2", "review",
                        "no", Instant.now())));

        assertThat(afterSecondReject.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(afterSecondReject.pendingTaskIds()).hasSize(1).doesNotContain(taskId);
        HumanTask rejected = provider.describeTask(taskId).orElseThrow();
        assertThat(rejected.status()).isEqualTo(HumanTaskStatus.REJECTED);
        assertThat(provider.history(id))
                .filteredOn(e -> e.eventType().equals("TASK_REJECTED"))
                .last()
                .satisfies(e -> {
                    assertThat(e.attributes()).containsEntry("reason",
                            "SIGN_COMPLETION_UNSATISFIABLE");
                    assertThat(e.attributes().get("rejectActionIds")).isNotBlank();
                });
    }

    @Test
    void rejectThresholdReachedTerminates() {
        WorkflowDefinition definition = definition("reject-threshold",
                human("review", spec(List.of("u1", "u2", "u3"), new AllApprove(),
                        new AtLeastRejects(2), Set.of("review"), false, Set.of(),
                        false, false, false, false, false, false, null, Map.of())));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");
        TaskId taskId = pendingTask(id).id();

        provider.act(new ActionRequest(id, new RejectAction(UUID.randomUUID().toString(),
                taskId, "u1", "review", "first", Instant.now())));
        WorkflowInstance afterSecond = provider.act(new ActionRequest(id,
                new RejectAction(UUID.randomUUID().toString(), taskId, "u2",
                        "review", "second", Instant.now())));

        assertThat(afterSecond.pendingTaskIds()).hasSize(1).doesNotContain(taskId);
        assertThat(provider.history(id))
                .filteredOn(e -> e.eventType().equals("TASK_REJECTED"))
                .last()
                .satisfies(e -> assertThat(e.attributes())
                        .containsEntry("reason", "REJECT_THRESHOLD_REACHED"));
    }

    @Test
    void beforeAddSuspendsOriginalAndResumesAfterCompletion() {
        HumanTaskSpec spec = spec(List.of("u1"), new AllApprove(), null, Set.of(),
                true, Set.of(AddAssigneePosition.BEFORE), false, false, false, false,
                false, false, Duration.ofSeconds(20), Map.of());
        WorkflowDefinition definition = definition("before", human("review", spec));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");
        TaskId originalTaskId = pendingTask(id).id();

        WorkflowInstance afterAdd = provider.act(new ActionRequest(id,
                new AddAssigneeAction(UUID.randomUUID().toString(), originalTaskId,
                        "u1", List.of(participant("u2")), AddAssigneePosition.BEFORE,
                        "pre-approval", Instant.now())));

        assertThat(afterAdd.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(afterAdd.currentStep()).isEqualTo("review");
        assertThat(afterAdd.pendingTaskIds()).hasSize(1).doesNotContain(originalTaskId);
        HumanTask original = provider.describeTask(originalTaskId).orElseThrow();
        assertThat(original.status()).isEqualTo(HumanTaskStatus.PENDING);
        assertThat(original.suspendedAssignees()).extracting(ParticipantSpec::value)
                .containsExactly("u1");
        assertThat(original.pendingAssignees()).isEmpty();
        assertThat(provider.listTasks(new TaskQuery(id, "u1", null, List.of(), null,
                HumanTaskStatus.PENDING, null, 100, null))).isEmpty();

        HumanTask beforeTask = pendingTask(id);
        assertThat(beforeTask.kind()).isEqualTo(HumanTaskKind.BEFORE_ADDED);
        assertThat(beforeTask.predecessorTaskId()).isEqualTo(originalTaskId);

        approve(id, beforeTask.id(), "u2");

        HumanTask resumed = provider.describeTask(originalTaskId).orElseThrow();
        assertThat(resumed.pendingAssignees()).extracting(ParticipantSpec::value)
                .containsExactly("u1");
        assertThat(provider.listTasks(new TaskQuery(id, "u1", null, List.of(), null,
                HumanTaskStatus.PENDING, null, 100, null))).hasSize(1);
        assertThat(eventTypes(id)).contains("TASK_ASSIGNMENT_SUSPENDED",
                "TASK_ASSIGNMENT_RESUMED", "TASK_ADD_ASSIGNEE_BEFORE");

        assertThat(approve(id, originalTaskId, "u1").status())
                .isEqualTo(WorkflowStatus.COMPLETED);
    }

    @Test
    void afterAddDefaultsToAnyApproveAndRunsBeforeNextStep() {
        HumanTaskSpec reviewSpec = spec(List.of("u1", "u2"), new AllApprove(), null,
                Set.of(), true, Set.of(AddAssigneePosition.AFTER), false, false, false,
                false, false, false, null, Map.of());
        WorkflowDefinition definition = definition("after",
                human("review", reviewSpec), human("final", basicSpec("u3")));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");
        TaskId reviewTaskId = pendingTask(id).id();

        provider.act(new ActionRequest(id, new AddAssigneeAction(
                UUID.randomUUID().toString(), reviewTaskId, "u1",
                List.of(participant("u4"), participant("u5")),
                AddAssigneePosition.AFTER, "post-approval", Instant.now())));

        assertThat(provider.describe(id).orElseThrow().pendingTaskIds())
                .containsExactly(reviewTaskId);

        approve(id, reviewTaskId, "u1");
        WorkflowInstance afterSecondApprove = approve(id, reviewTaskId, "u2");

        assertThat(afterSecondApprove.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(afterSecondApprove.currentStep()).isEqualTo("review");
        List<HumanTask> pending = pendingTasks(id);
        assertThat(pending).hasSize(1);
        HumanTask afterTask = pending.get(0);
        assertThat(afterTask.kind()).isEqualTo(HumanTaskKind.AFTER_ADDED);
        assertThat(afterTask.predecessorTaskId()).isEqualTo(reviewTaskId);
        assertThat(afterTask.pendingAssignees()).hasSize(2);

        WorkflowInstance afterOneOfTwo = approve(id, afterTask.id(), "u4");
        assertThat(afterOneOfTwo.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(afterOneOfTwo.currentStep()).isEqualTo("final");
        HumanTask completedAfter = provider.describeTask(afterTask.id()).orElseThrow();
        assertThat(completedAfter.status()).isEqualTo(HumanTaskStatus.COMPLETED);
        assertThat(completedAfter.assignments())
                .filteredOn(a -> a.participant().value().equals("u5"))
                .singleElement()
                .satisfies(a -> assertThat(a.status()).isEqualTo(AssignmentStatus.CANCELLED));
        assertThat(eventTypes(id)).contains("TASK_ADD_ASSIGNEE_AFTER");

        TaskId finalTaskId = pendingTask(id).id();
        assertThat(approve(id, finalTaskId, "u3").status())
                .isEqualTo(WorkflowStatus.COMPLETED);
    }

    @Test
    void transferMovesOwnershipWithoutRecord() {
        HumanTaskSpec spec = spec(List.of("u1"), new AllApprove(), null, Set.of(),
                false, Set.of(), false, true, false, false, false, false, null, Map.of());
        WorkflowDefinition definition = definition("transfer", human("review", spec));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");
        TaskId taskId = pendingTask(id).id();

        provider.act(new ActionRequest(id, new TransferAction(UUID.randomUUID().toString(),
                taskId, "u1", participant("u2"), "handover", Instant.now())));

        HumanTask transferred = provider.describeTask(taskId).orElseThrow();
        assertThat(transferred.assignments())
                .filteredOn(a -> a.participant().value().equals("u1"))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.status()).isEqualTo(AssignmentStatus.TRANSFERRED);
                    assertThat(a.record()).isNull();
                });
        assertThat(provider.listTasks(new TaskQuery(id, "u2", null, List.of(), null,
                HumanTaskStatus.PENDING, null, 100, null))).hasSize(1);
        assertThat(approve(id, taskId, "u2").status()).isEqualTo(WorkflowStatus.COMPLETED);
    }

    @Test
    void delegationApprovesOnBehalfAndIsVisibleToBothPrincipals() {
        HumanTaskSpec spec = spec(List.of("u1", "u3"), new AllApprove(), null, Set.of(),
                false, Set.of(), false, false, true, false, false, false, null, Map.of());
        WorkflowDefinition definition = definition("delegate", human("review", spec));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");
        TaskId taskId = pendingTask(id).id();

        provider.act(new ActionRequest(id, new DelegateAction(UUID.randomUUID().toString(),
                taskId, "u1", participant("u2"), Duration.ofSeconds(30), "proxy", Instant.now())));

        HumanTask delegated = provider.describeTask(taskId).orElseThrow();
        assertThat(delegated.assignments())
                .filteredOn(a -> a.participant().value().equals("u1"))
                .singleElement()
                .satisfies(a -> assertThat(a.status()).isEqualTo(AssignmentStatus.DELEGATED));

        approve(id, taskId, "u2");

        HumanTask afterProxy = provider.describeTask(taskId).orElseThrow();
        assertThat(afterProxy.status()).isEqualTo(HumanTaskStatus.PENDING);
        assertThat(afterProxy.approveRecords())
                .anySatisfy(r -> {
                    assertThat(r.assignee()).isEqualTo("u2");
                    assertThat(r.onBehalfOf()).isEqualTo("u1");
                });
        assertThat(afterProxy.assignments())
                .filteredOn(a -> a.participant().value().equals("u1"))
                .singleElement()
                .satisfies(a -> assertThat(a.status()).isEqualTo(AssignmentStatus.DELEGATED));

        assertThat(provider.listTasks(new TaskQuery(id, null, "u1", List.of(), null,
                null, null, 100, null))).hasSize(1);
        assertThat(provider.listTasks(new TaskQuery(id, null, "u2", List.of(), null,
                null, null, 100, null))).hasSize(1);

        approve(id, taskId, "u3");
        HumanTask completed = provider.describeTask(taskId).orElseThrow();
        assertThat(completed.status()).isEqualTo(HumanTaskStatus.COMPLETED);
        assertThat(completed.approveRecords()).hasSize(2);
    }

    @Test
    void delegationExpiryRestoresOriginalAssignee() {
        HumanTaskSpec spec = spec(List.of("u1"), new AllApprove(), null, Set.of(),
                false, Set.of(), false, false, true, false, false, false, null, Map.of());
        WorkflowDefinition definition = definition("delegate-expiry", human("review", spec));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");
        TaskId taskId = pendingTask(id).id();

        provider.act(new ActionRequest(id, new DelegateAction(UUID.randomUUID().toString(),
                taskId, "u1", participant("u2"), Duration.ofMillis(150), "proxy", Instant.now())));

        HumanTask restored = awaitAssignment(taskId, "u1", AssignmentStatus.PENDING);
        assertThat(restored.assignments())
                .filteredOn(a -> a.participant().value().equals("u2"))
                .singleElement()
                .satisfies(a -> assertThat(a.status()).isEqualTo(AssignmentStatus.CANCELLED));
        assertThat(eventTypes(id)).contains("DELEGATION_EXPIRED");

        assertThat(approve(id, taskId, "u1").status()).isEqualTo(WorkflowStatus.COMPLETED);
    }

    @Test
    void taskWithdrawKeepsRecordAndRollsBack() {
        HumanTaskSpec spec = spec(List.of("u1", "u2"), new AllApprove(), null, Set.of(),
                false, Set.of(), false, false, false, true, false, false, null, Map.of());
        WorkflowDefinition definition = definition("withdraw", human("review", spec));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");
        TaskId taskId = pendingTask(id).id();
        approve(id, taskId, "u1");

        WorkflowInstance afterWithdraw = provider.act(new ActionRequest(id,
                new WithdrawAction(UUID.randomUUID().toString(), taskId, "u1",
                        "review", "changed my mind", Instant.now())));

        assertThat(afterWithdraw.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(afterWithdraw.pendingTaskIds()).hasSize(1).doesNotContain(taskId);
        HumanTask withdrawn = provider.describeTask(taskId).orElseThrow();
        assertThat(withdrawn.status()).isEqualTo(HumanTaskStatus.WITHDRAWN);
        assertThat(withdrawn.approveRecords()).hasSize(1);
        assertThat(withdrawn.approveRecords().get(0).decision())
                .isEqualTo(ActionDecision.APPROVE);

        List<String> events = eventTypes(id);
        assertThat(events).containsSubsequence("TASK_WITHDRAWN", "INSTANCE_ROLLED_BACK");
    }

    @Test
    void withdrawCancelsIntermediateActiveTasksWithTaskCancelledEvents() {
        HumanTaskSpec midSpec = spec(List.of("u9"), new AllApprove(), null, Set.of(),
                true, Set.of(AddAssigneePosition.AFTER), false, false, false, false,
                false, false, null, Map.of());
        HumanTaskSpec reviewSpec = spec(List.of("u1", "u2"), new AllApprove(), null,
                Set.of(), false, Set.of(), false, false, false, true, false, false,
                null, Map.of());
        WorkflowDefinition definition = definition("withdraw-middle",
                activity("start"), human("mid", midSpec), human("review", reviewSpec));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");

        TaskId midTaskId = pendingTask(id).id();
        approve(id, midTaskId, "u9");
        TaskId reviewTaskId = pendingTask(id).id();

        provider.act(new ActionRequest(id, new AddAssigneeAction(
                UUID.randomUUID().toString(), midTaskId, "u9",
                List.of(participant("u8")), AddAssigneePosition.AFTER,
                "parallel check", Instant.now())));
        TaskId afterTaskId = pendingTasks(id).stream()
                .filter(t -> t.kind() == HumanTaskKind.AFTER_ADDED)
                .findFirst().orElseThrow().id();

        approve(id, reviewTaskId, "u1");
        provider.act(new ActionRequest(id, new WithdrawAction(
                UUID.randomUUID().toString(), reviewTaskId, "u1", "start",
                "rollback", Instant.now())));

        assertThat(provider.describeTask(afterTaskId).orElseThrow().status())
                .isEqualTo(HumanTaskStatus.CANCELLED);
        assertThat(eventTypes(id)).containsSubsequence("TASK_WITHDRAWN",
                "TASK_ASSIGNMENT_AUTO_CANCELLED", "TASK_CANCELLED",
                "INSTANCE_ROLLED_BACK");
    }

    @Test
    void instanceWithdrawCancelsInstanceAndActiveAssignments() {
        WorkflowDefinition definition = definition("withdraw-instance",
                human("review", basicSpec("u1")));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");
        TaskId taskId = pendingTask(id).id();

        WorkflowInstance withdrawn = provider.act(new ActionRequest(id,
                new WithdrawInstanceAction(UUID.randomUUID().toString(), "starter",
                        "review", "no longer needed", Instant.now())));

        assertThat(withdrawn.status()).isEqualTo(WorkflowStatus.CANCELLED);
        HumanTask task = provider.describeTask(taskId).orElseThrow();
        assertThat(task.status()).isEqualTo(HumanTaskStatus.WITHDRAWN);
        assertThat(task.assignments()).allSatisfy(a -> {
            assertThat(a.status()).isEqualTo(AssignmentStatus.CANCELLED);
            assertThat(a.resolvedReason()).isEqualTo("WITHDRAWN");
        });
        assertThat(eventTypes(id)).containsSubsequence("TASK_WITHDRAWN", "INSTANCE_WITHDRAWN")
                .contains("INSTANCE_ROLLED_BACK");
    }

    @Test
    void dueInTimeoutFailsInstanceByDefault() {
        HumanTaskSpec spec = spec(List.of("u1"), new AllApprove(), null, Set.of(),
                false, Set.of(), false, false, false, false, false, false,
                Duration.ofMillis(150), Map.of());
        WorkflowDefinition definition = definition("overdue", human("review", spec));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");
        TaskId taskId = pendingTask(id).id();

        WorkflowInstance timedOut = awaitStatus(id, WorkflowStatus.FAILED);

        assertThat(timedOut.failure()).isNotNull();
        assertThat(timedOut.failure().errorType()).isEqualTo("TASK_OVERDUE");
        HumanTask cancelled = provider.describeTask(taskId).orElseThrow();
        assertThat(cancelled.status()).isEqualTo(HumanTaskStatus.CANCELLED);
        assertThat(cancelled.assignments())
                .allSatisfy(a -> assertThat(a.status()).isEqualTo(AssignmentStatus.CANCELLED));
        assertThat(eventTypes(id)).contains("TASK_OVERDUE", "INSTANCE_FAILED");
    }

    @Test
    void overdueTaskCanWaitAndBeResumed() {
        HumanTaskSpec spec = spec(List.of("u1"), new AllApprove(), null, Set.of(),
                false, Set.of(), false, false, false, false, false, false,
                Duration.ofMillis(150), Map.of("timeoutPolicy", "WAITING"));
        WorkflowDefinition definition = definition("waiting", human("review", spec));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");
        TaskId oldTaskId = pendingTask(id).id();

        WorkflowInstance waiting = awaitStatus(id, WorkflowStatus.WAITING);
        assertThat(waiting.failure()).isNull();
        assertThat(provider.describeTask(oldTaskId).orElseThrow().status())
                .isEqualTo(HumanTaskStatus.CANCELLED);

        WorkflowInstance resumed = provider.act(new ActionRequest(id,
                new ResumeWorkflowAction(UUID.randomUUID().toString(), "starter",
                        Duration.ofSeconds(2), "extended", Instant.now())));

        assertThat(resumed.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(resumed.pendingTaskIds()).hasSize(1).doesNotContain(oldTaskId);
        assertThat(eventTypes(id)).containsSubsequence("INSTANCE_RESUMED", "TASK_CREATED");

        TaskId newTaskId = resumed.pendingTaskIds().get(0);
        assertThat(approve(id, newTaskId, "u1").status())
                .isEqualTo(WorkflowStatus.COMPLETED);
    }

    @Test
    void urgeAllowedWhileSuspendedAndRejectedWhenTerminal() {
        HumanTaskSpec spec = spec(List.of("u1"), new AllApprove(), null, Set.of(),
                true, Set.of(AddAssigneePosition.BEFORE), false, false, false, false,
                true, false, null, Map.of());
        WorkflowDefinition definition = definition("urge", human("review", spec));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");
        TaskId originalTaskId = pendingTask(id).id();
        provider.act(new ActionRequest(id, new AddAssigneeAction(UUID.randomUUID().toString(),
                originalTaskId, "u1", List.of(participant("u2")),
                AddAssigneePosition.BEFORE, "pre", Instant.now())));

        provider.act(new ActionRequest(id, new UrgeAction(UUID.randomUUID().toString(),
                originalTaskId, "u1", "please hurry", Instant.now())));

        assertThat(provider.history(id))
                .filteredOn(e -> e.eventType().equals("TASK_URGED"))
                .singleElement()
                .satisfies(e -> assertThat(e.attributes())
                        .containsEntry("targetStatus", "SUSPENDED"));

        TaskId beforeTaskId = pendingTask(id).id();
        approve(id, beforeTaskId, "u2");
        approve(id, originalTaskId, "u1");

        assertThatThrownBy(() -> provider.act(new ActionRequest(id,
                new UrgeAction(UUID.randomUUID().toString(), originalTaskId,
                        "u1", "again", Instant.now()))))
                .isInstanceOf(HumanTaskStateConflictException.class);
    }

    @Test
    void ccRecordsEventWithoutChangingStateMachine() {
        HumanTaskSpec spec = spec(List.of("u1"), new AllApprove(), null, Set.of(),
                false, Set.of(), false, false, false, false, false, true, null, Map.of());
        WorkflowDefinition definition = definition("cc", human("review", spec));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");
        TaskId taskId = pendingTask(id).id();

        WorkflowInstance afterCc = provider.act(new ActionRequest(id,
                new CcAction(UUID.randomUUID().toString(), "u9",
                        List.of(participant("watcher")), "review",
                        "fyi", Instant.now())));

        assertThat(afterCc.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(provider.describeTask(taskId).orElseThrow().status())
                .isEqualTo(HumanTaskStatus.PENDING);
        assertThat(eventTypes(id)).contains("TASK_CC");

        approve(id, taskId, "u1");
        assertThatThrownBy(() -> provider.act(new ActionRequest(id,
                new CcAction(UUID.randomUUID().toString(), "u9",
                        List.of(participant("watcher")), null, "late", Instant.now()))))
                .isInstanceOf(WorkflowStateConflictException.class);
    }

    @Test
    void waitSignalRequiresMatchingName() {
        WorkflowDefinition definition = definition("signal",
                StepDefinition.waitSignal("wait", "payment.settled",
                        Duration.ofSeconds(30), List.of()),
                activity("done"));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");

        WorkflowInstance waiting = provider.describe(id).orElseThrow();
        assertThat(waiting.currentStep()).isEqualTo("wait");
        assertThat(waiting.pendingTaskIds()).isEmpty();

        assertThatThrownBy(() -> provider.act(new ActionRequest(id,
                new SignalAction(UUID.randomUUID().toString(), "wrong.signal",
                        Map.of(), Instant.now()))))
                .isInstanceOf(WorkflowStateConflictException.class);

        WorkflowInstance completed = provider.act(new ActionRequest(id,
                new SignalAction(UUID.randomUUID().toString(), "payment.settled",
                        Map.of("paid", "true"), Instant.now())));

        assertThat(completed.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(completed.variables()).containsEntry("paid", "true");
    }

    @Test
    void pendingTaskIdsMatchPendingForQuery() {
        WorkflowDefinition definition = definition("pending-consistency",
                human("review", basicSpec("u1", "u2")));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");

        WorkflowInstance instance = provider.describe(id).orElseThrow();
        List<TaskId> byPendingFor = provider.listTasks(new TaskQuery(id, "u1", null,
                        List.of(), null, HumanTaskStatus.PENDING, null, 100, null))
                .stream().map(HumanTask::id).toList();

        assertThat(byPendingFor).containsExactlyElementsOf(instance.pendingTaskIds());
    }

    @Test
    void afterSpawnIsDiscardedWhenTaskIsRejected() {
        HumanTaskSpec spec = spec(List.of("u1", "u2"), new AllApprove(),
                new AnyRejectTerminates(), Set.of("review"), true,
                Set.of(AddAssigneePosition.AFTER), false, false, false, false, false,
                false, null, Map.of());
        WorkflowDefinition definition = definition("after-discard", human("review", spec));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");
        TaskId taskId = pendingTask(id).id();

        provider.act(new ActionRequest(id, new AddAssigneeAction(UUID.randomUUID().toString(),
                taskId, "u1", List.of(participant("u5")), AddAssigneePosition.AFTER,
                "post", Instant.now())));
        provider.act(new ActionRequest(id, new RejectAction(UUID.randomUUID().toString(),
                taskId, "u1", "review", "rejected", Instant.now())));

        assertThat(pendingTasks(id)).singleElement()
                .satisfies(t -> assertThat(t.kind()).isEqualTo(HumanTaskKind.ORIGINAL));
        assertThat(provider.history(id))
                .filteredOn(e -> e.eventType().equals("TASK_AFTER_SPAWN_DISCARDED"))
                .singleElement()
                .satisfies(e -> assertThat(e.attributes()).containsEntry("reason", "REJECTED"));
    }

    @Test
    void afterPendingSurvivesBeforeCompletionAndDiscardsOnBeforeReject() {
        HumanTaskSpec spec = spec(List.of("u1"), new AllApprove(),
                new AnyRejectTerminates(), Set.of("review"), true,
                Set.of(AddAssigneePosition.BEFORE, AddAssigneePosition.AFTER), false,
                false, false, false, false, false, null, Map.of());
        WorkflowDefinition definition = definition("after-before", human("review", spec));
        provider.register(definition);

        WorkflowInstanceId completedPath = start(definition, "starter");
        TaskId originalTaskId = pendingTask(completedPath).id();
        provider.act(new ActionRequest(completedPath, new AddAssigneeAction(
                UUID.randomUUID().toString(), originalTaskId, "u1",
                List.of(participant("u3")), AddAssigneePosition.AFTER, "post",
                Instant.now())));
        provider.act(new ActionRequest(completedPath, new AddAssigneeAction(
                UUID.randomUUID().toString(), originalTaskId, "u1",
                List.of(participant("u2")), AddAssigneePosition.BEFORE, "pre",
                Instant.now())));
        TaskId beforeTaskId = pendingTask(completedPath).id();
        approve(completedPath, beforeTaskId, "u2");
        assertThat(eventTypes(completedPath)).doesNotContain("TASK_AFTER_SPAWN_DISCARDED");
        approve(completedPath, originalTaskId, "u1");
        HumanTask spawnedAfter = pendingTask(completedPath);
        assertThat(spawnedAfter.kind()).isEqualTo(HumanTaskKind.AFTER_ADDED);
        assertThat(approve(completedPath, spawnedAfter.id(), "u3").status())
                .isEqualTo(WorkflowStatus.COMPLETED);

        WorkflowInstanceId rejectedPath = start(definition, "starter");
        TaskId rejectedOriginalId = pendingTask(rejectedPath).id();
        provider.act(new ActionRequest(rejectedPath, new AddAssigneeAction(
                UUID.randomUUID().toString(), rejectedOriginalId, "u1",
                List.of(participant("u3")), AddAssigneePosition.AFTER, "post",
                Instant.now())));
        provider.act(new ActionRequest(rejectedPath, new AddAssigneeAction(
                UUID.randomUUID().toString(), rejectedOriginalId, "u1",
                List.of(participant("u2")), AddAssigneePosition.BEFORE, "pre",
                Instant.now())));
        TaskId rejectedBeforeId = pendingTask(rejectedPath).id();
        provider.act(new ActionRequest(rejectedPath, new RejectAction(
                UUID.randomUUID().toString(), rejectedBeforeId, "u2", "review",
                "no", Instant.now())));

        assertThat(provider.history(rejectedPath))
                .filteredOn(e -> e.eventType().equals("TASK_AFTER_SPAWN_DISCARDED"))
                .singleElement()
                .satisfies(e -> assertThat(e.attributes())
                        .containsEntry("reason", "UPSTREAM_ROLLBACK"));
        assertThat(pendingTasks(rejectedPath)).singleElement()
                .satisfies(t -> assertThat(t.kind()).isEqualTo(HumanTaskKind.ORIGINAL));
    }

    @Test
    void terminalTaskCancelsDelegatedAssignment() {
        HumanTaskSpec spec = spec(List.of("u1", "u3"), new AllApprove(), null, Set.of(),
                false, Set.of(), false, false, true, false, false, false, null, Map.of());
        WorkflowDefinition definition = definition("delegation-terminal",
                human("review", spec));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");
        TaskId taskId = pendingTask(id).id();

        provider.act(new ActionRequest(id, new DelegateAction(UUID.randomUUID().toString(),
                taskId, "u1", participant("u2"), Duration.ofSeconds(30), "proxy",
                Instant.now())));
        approve(id, taskId, "u2");
        approve(id, taskId, "u3");

        HumanTask completed = provider.describeTask(taskId).orElseThrow();
        assertThat(completed.status()).isEqualTo(HumanTaskStatus.COMPLETED);
        assertThat(completed.assignments())
                .filteredOn(a -> a.participant().value().equals("u1"))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.status()).isEqualTo(AssignmentStatus.CANCELLED);
                    assertThat(a.resolvedReason()).isEqualTo("RULE_SATISFIED");
                });
        assertThat(completed.approveRecords())
                .anySatisfy(r -> assertThat(r.onBehalfOf()).isEqualTo("u1"));
    }

    @Test
    void atLeastPercentageRoundsUp() {
        HumanTaskSpec spec = spec(List.of("u1", "u2"), new AtLeastPercentage(0.75),
                null, Set.of(), false, Set.of(), false, false, false, false, false,
                false, null, Map.of());
        WorkflowDefinition definition = definition("percentage", human("review", spec));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");
        TaskId taskId = pendingTask(id).id();

        assertThat(approve(id, taskId, "u1").status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(approve(id, taskId, "u2").status()).isEqualTo(WorkflowStatus.COMPLETED);
    }

    @Test
    void removeAssigneeWritesEventsAndKeepsRuleComputable() {
        HumanTaskSpec spec = spec(List.of("u1", "u2"), new AtLeastCount(1), null,
                Set.of(), false, Set.of(), true, false, false, false, false, false,
                null, Map.of());
        WorkflowDefinition definition = definition("remove", human("review", spec));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");
        TaskId taskId = pendingTask(id).id();

        provider.act(new ActionRequest(id, new RemoveAssigneeAction(
                UUID.randomUUID().toString(), taskId, "u1", List.of("u2"),
                "left company", Instant.now())));

        HumanTask task = provider.describeTask(taskId).orElseThrow();
        assertThat(task.assignments())
                .filteredOn(a -> a.participant().value().equals("u2"))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.status()).isEqualTo(AssignmentStatus.CANCELLED);
                    assertThat(a.resolvedReason()).isEqualTo("REMOVED");
                });
        assertThat(eventTypes(id)).contains("TASK_ASSIGNEE_REMOVED");
        assertThat(approve(id, taskId, "u1").status()).isEqualTo(WorkflowStatus.COMPLETED);
    }

    @Test
    void cancelAndTerminateAreTerminal() {
        WorkflowDefinition definition = definition("cancel",
                human("review", basicSpec("u1")));
        provider.register(definition);

        WorkflowInstanceId cancelledId = start(definition, "starter");
        TaskId cancelledTaskId = pendingTask(cancelledId).id();
        WorkflowInstance cancelled = provider.cancel(new CancelWorkflowRequest(
                cancelledId, "stop", true));
        assertThat(cancelled.status()).isEqualTo(WorkflowStatus.CANCELLED);
        assertThat(provider.describeTask(cancelledTaskId).orElseThrow().status())
                .isEqualTo(HumanTaskStatus.CANCELLED);
        assertThat(provider.history(cancelledId))
                .filteredOn(e -> e.eventType().equals("INSTANCE_CANCELLED"))
                .singleElement()
                .satisfies(e -> assertThat(e.attributes())
                        .containsEntry("compensate", "true"));

        WorkflowInstanceId terminatedId = start(definition, "starter");
        assertThat(provider.terminate(new TerminateWorkflowRequest(
                terminatedId, "stop" )).status()).isEqualTo(WorkflowStatus.TERMINATED);
    }

    @Test
    void resumeRequiresWaitingInstance() {
        WorkflowDefinition definition = definition("resume-guard",
                human("review", basicSpec("u1")));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");

        assertThatThrownBy(() -> provider.act(new ActionRequest(id,
                new ResumeWorkflowAction(UUID.randomUUID().toString(), "starter",
                        Duration.ofSeconds(1), "not waiting", Instant.now()))))
                .isInstanceOf(WorkflowStateConflictException.class);
    }

    @Test
    void withdrawCannotUndoARejectRecord() {
        HumanTaskSpec spec = spec(List.of("u1", "u2"), new AllApprove(),
                new RecordAndContinue(), Set.of("review"), false, Set.of(), false,
                false, false, true, false, false, null, Map.of());
        WorkflowDefinition definition = definition("withdraw-reject",
                human("review", spec));
        provider.register(definition);
        WorkflowInstanceId id = start(definition, "starter");
        TaskId taskId = pendingTask(id).id();

        provider.act(new ActionRequest(id, new RejectAction(UUID.randomUUID().toString(),
                taskId, "u1", "review", "no", Instant.now())));

        assertThatThrownBy(() -> provider.act(new ActionRequest(id,
                new WithdrawAction(UUID.randomUUID().toString(), taskId, "u1",
                        "review", "undo", Instant.now()))))
                .isInstanceOf(WorkflowStateConflictException.class);
    }

    @Test
    void expressionParticipantsAreResolvedFromVariables() {
        HumanTaskSpec spec = new HumanTaskSpec(
                List.of(new ParticipantSpec(ParticipantType.EXPRESSION, "${approver}",
                        Map.of())),
                new AllApprove(), null, Set.of(), null, Map.of(), false, Set.of(),
                false, false, false, false, false, false, null, Map.of());
        WorkflowDefinition definition = definition("expression", human("review", spec));
        provider.register(definition);

        WorkflowInstanceId id = new WorkflowInstanceId("wf-" + UUID.randomUUID());
        provider.start(new StartWorkflowRequest(id, definition.id(), definition.version(),
                "starter", Map.of("approver", "u7")));

        HumanTask task = pendingTask(id);
        assertThat(task.pendingAssignees()).extracting(ParticipantSpec::value)
                .containsExactly("u7");
        assertThat(approve(id, task.id(), "u7").status())
                .isEqualTo(WorkflowStatus.COMPLETED);

        WorkflowInstanceId unresolved = new WorkflowInstanceId("wf-" + UUID.randomUUID());
        assertThatThrownBy(() -> provider.start(new StartWorkflowRequest(unresolved,
                definition.id(), definition.version(), "starter", Map.of())))
                .isInstanceOf(ParticipantResolutionException.class);
    }

    @Test
    void searchAndVersionConflictFollowContract() {
        WorkflowDefinition v1 = new WorkflowDefinition(new WorkflowDefinitionId("contract",
                "versions"), new WorkflowDefinitionVersion(1, 0),
                List.of(human("review", basicSpec("u1"))), Duration.ofSeconds(30),
                null, Map.of());
        provider.register(v1);
        WorkflowInstanceId id = start(v1, "starter");

        assertThat(provider.search(new WorkflowSearchQuery(v1.id(),
                WorkflowStatus.RUNNING, null, null, 10, null))).hasSize(1);
        assertThat(provider.history(id)).isNotEmpty();

        WorkflowDefinition v2 = new WorkflowDefinition(v1.id(),
                new WorkflowDefinitionVersion(2, 0), v1.steps(),
                Duration.ofSeconds(30), null, Map.of());
        provider.register(v2);

        assertThatThrownBy(() -> provider.start(new StartWorkflowRequest(id, v1.id(),
                v2.version(), "starter", Map.of())))
                .isInstanceOf(WorkflowVersionConflictException.class);
    }

    // ------------------------------------------------------------------
    // 测试工具
    // ------------------------------------------------------------------

    protected static WorkflowDefinition definition(String name, StepDefinition... steps) {
        return new WorkflowDefinition(new WorkflowDefinitionId("contract", name),
                new WorkflowDefinitionVersion(1, 0), List.of(steps),
                Duration.ofSeconds(30), null, Map.of());
    }

    protected static StepDefinition human(String stepName, HumanTaskSpec spec) {
        return new StepDefinition(stepName, StepKind.HUMAN_TASK, Duration.ofSeconds(30),
                RetryPolicy.noRetry(), List.of(), Map.of(), spec, null);
    }

    protected static StepDefinition activity(String stepName) {
        return new StepDefinition(stepName, StepKind.ACTIVITY, Duration.ofSeconds(30),
                RetryPolicy.noRetry(), List.of(), Map.of());
    }

    protected static ParticipantSpec participant(String value) {
        return new ParticipantSpec(ParticipantType.USER, value, Map.of());
    }

    protected static HumanTaskSpec basicSpec(String... users) {
        return spec(List.of(users), new AllApprove(), null, Set.of(), false, Set.of(),
                false, false, false, false, false, false, null, Map.of());
    }

    protected static HumanTaskSpec spec(List<String> users, SignCompletion signCompletion,
                                        RejectCompletion rejectCompletion,
                                        Set<String> rejectTargets,
                                        boolean allowAddAssignee,
                                        Set<AddAssigneePosition> addPositions,
                                        boolean allowRemoveAssignee, boolean allowTransfer,
                                        boolean allowDelegate, boolean allowWithdraw,
                                        boolean allowUrge, boolean allowCc,
                                        Duration dueIn, Map<String, String> metadata) {
        return new HumanTaskSpec(users.stream().map(WorkflowProviderContractTest::participant).toList(),
                signCompletion, rejectCompletion, rejectTargets, null, Map.of(),
                allowAddAssignee, addPositions, allowRemoveAssignee, allowTransfer,
                allowDelegate, allowWithdraw, allowUrge, allowCc, dueIn, metadata);
    }

    protected WorkflowInstanceId start(WorkflowDefinition definition, String startedBy) {
        WorkflowInstanceId id = new WorkflowInstanceId("wf-" + UUID.randomUUID());
        provider.start(new StartWorkflowRequest(id, definition.id(),
                definition.version(), startedBy, Map.of()));
        return id;
    }

    protected WorkflowInstance approve(WorkflowInstanceId id, TaskId taskId, String assignee) {
        return provider.act(new ActionRequest(id, new ApproveAction(
                UUID.randomUUID().toString(), taskId, assignee, "ok", Map.of(),
                Instant.now())));
    }

    protected HumanTask pendingTask(WorkflowInstanceId id) {
        List<HumanTask> tasks = pendingTasks(id);
        assertThat(tasks).as("pending tasks of %s", id.value()).hasSize(1);
        return tasks.get(0);
    }

    protected List<HumanTask> pendingTasks(WorkflowInstanceId id) {
        return provider.listTasks(new TaskQuery(id, null, null, List.of(), null,
                HumanTaskStatus.PENDING, null, 100, null));
    }

    protected List<String> eventTypes(WorkflowInstanceId id) {
        return provider.history(id).stream().map(WorkflowHistoryEvent::eventType).toList();
    }

    protected WorkflowInstance awaitStatus(WorkflowInstanceId id, WorkflowStatus status) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        WorkflowInstance last = null;
        while (System.nanoTime() < deadline) {
            last = provider.describe(id).orElseThrow();
            if (last.status() == status) {
                return last;
            }
            sleep();
        }
        return fail("instance %s did not reach %s, last status %s", id.value(), status,
                last == null ? "absent" : last.status());
    }

    protected HumanTask awaitAssignment(TaskId taskId, String assignee,
                                        AssignmentStatus status) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        HumanTask last = null;
        while (System.nanoTime() < deadline) {
            last = provider.describeTask(taskId).orElseThrow();
            boolean matched = last.assignments().stream()
                    .anyMatch(a -> a.participant().value().equals(assignee)
                            && a.status() == status);
            if (matched) {
                return last;
            }
            sleep();
        }
        return fail("assignment %s of task %s did not reach %s, last was %s",
                assignee, taskId.value(), status,
                last == null ? "absent" : last.assignments());
    }

    private static void sleep() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
