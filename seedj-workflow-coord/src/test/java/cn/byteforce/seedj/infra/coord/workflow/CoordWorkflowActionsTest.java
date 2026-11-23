package cn.byteforce.seedj.infra.coord.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.byteforce.coord.sdk.workflow.AddAssigneePosition;
import cn.byteforce.coord.sdk.workflow.ApprovalAssignment;
import cn.byteforce.coord.sdk.workflow.ApprovalBranch;
import cn.byteforce.coord.sdk.workflow.ApprovalInstanceDetail;
import cn.byteforce.coord.sdk.workflow.ApprovalRound;
import cn.byteforce.coord.sdk.workflow.ApprovalTask;
import cn.byteforce.coord.sdk.workflow.WorkflowClient;
import cn.byteforce.seedj.infra.coord.workflow.CoordWorkflowSidecar.ServerClock;
import cn.byteforce.seedj.workflow.spi.AddAssigneeAction;
import cn.byteforce.seedj.workflow.spi.AllApprove;
import cn.byteforce.seedj.workflow.spi.ApproveAction;
import cn.byteforce.seedj.workflow.spi.CcAction;
import cn.byteforce.seedj.workflow.spi.HumanTaskSpec;
import cn.byteforce.seedj.workflow.spi.ParticipantSpec;
import cn.byteforce.seedj.workflow.spi.ParticipantType;
import cn.byteforce.seedj.workflow.spi.RejectAction;
import cn.byteforce.seedj.workflow.spi.RejectTargetNotAllowedException;
import cn.byteforce.seedj.workflow.spi.RemoveAssigneeAction;
import cn.byteforce.seedj.workflow.spi.ResumeWorkflowAction;
import cn.byteforce.seedj.workflow.spi.RetryPolicy;
import cn.byteforce.seedj.workflow.spi.SignalAction;
import cn.byteforce.seedj.workflow.spi.StepDefinition;
import cn.byteforce.seedj.workflow.spi.StepKind;
import cn.byteforce.seedj.workflow.spi.TaskId;
import cn.byteforce.seedj.workflow.spi.WithdrawInstanceAction;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinition;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionId;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionVersion;
import cn.byteforce.seedj.workflow.spi.WorkflowInstanceId;
import cn.byteforce.seedj.workflow.spi.WorkflowProviderException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** T10–T12：actor 策略、驳回路由、sidecar 写入顺序与管理动作的 SDK 参数。 */
class CoordWorkflowActionsTest {

    private static final String INSTANCE = "wf-actions";
    private static final String ROUND = INSTANCE + ":round-0001";
    private static final String SEAT = "seat-1";
    private static final String BRANCH = "branch-1";
    private static final String NODE = "review";
    private static final Instant NOW = Instant.parse("2026-11-22T09:00:00Z");

    private final WorkflowClient workflows = mock(WorkflowClient.class);
    private final CoordWorkflowReads reads = mock(CoordWorkflowReads.class);
    private final CoordWorkflowSidecar sidecar = mock(CoordWorkflowSidecar.class);
    private final WorkflowInstanceId instanceId = new WorkflowInstanceId(INSTANCE);
    private final TaskId taskId = new TaskId(ROUND);

    private CoordWorkflowActions actions;
    private ApprovalInstanceDetail detail;

    @BeforeEach
    void setUp() {
        actions =
                new CoordWorkflowActions(
                        workflows, reads, sidecar, new ServerClock(), "sign-admin");
        detail = detail();
        when(reads.detailOrNull(INSTANCE)).thenReturn(detail);
        when(reads.definitionOf(detail)).thenReturn(definition(Set.of("start", "done")));
    }

    @Test
    void approveActsOnBehalfOfTheAssigneeAfterPersistingTheForm() {
        actions.approve(
                instanceId,
                new ApproveAction("a-1", taskId, "u1", "ok", Map.of("amount", "10"), NOW));

        InOrder order = inOrder(sidecar, workflows);
        order.verify(sidecar).putForm(INSTANCE, ROUND, "a-1", NOW, Map.of("amount", "10"));
        order.verify(sidecar)
                .putAction(INSTANCE, "a-1", Map.of("operator", "u1", "targetStatus", "APPROVED"));
        order.verify(workflows).approveOnBehalfOf(INSTANCE, SEAT, "ok", "a-1", "u1");
    }

    @Test
    void approveIsRejectedWhenTheFormCannotBePersisted() {
        doThrow(new WorkflowProviderException("cannot persist form data"))
                .when(sidecar)
                .putForm(anyString(), anyString(), anyString(), any(), any());

        assertThatThrownBy(
                        () ->
                                actions.approve(
                                        instanceId,
                                        new ApproveAction(
                                                "a-2", taskId, "u1", "ok", Map.of("k", "v"), NOW)))
                .isInstanceOf(WorkflowProviderException.class)
                .hasMessageContaining("form data");
        verifyNoInteractions(workflows);
    }

    @Test
    void rejectOnAForwardTargetUsesAPlainReject() {
        actions.reject(
                instanceId, new RejectAction("a-3", taskId, "u1", "done", "not enough", NOW));

        verify(workflows).rejectOnBehalfOf(INSTANCE, SEAT, "not enough", "a-3", "u1");
        verify(sidecar).putAction(INSTANCE, "a-3", Map.of("operator", "u1", "targetStep", "done"));
    }

    @Test
    void rejectOnAnEnteredUpstreamTargetRollsBackToThatNode() {
        actions.reject(instanceId, new RejectAction("a-4", taskId, "u1", "start", "rework", NOW));

        verify(workflows).rejectToNodeOnBehalfOf(INSTANCE, SEAT, "start", "rework", "a-4", "u1");
    }

    @Test
    void rejectOutsideTheWhitelistFailsClosed() {
        assertThatThrownBy(
                        () ->
                                actions.reject(
                                        instanceId,
                                        new RejectAction(
                                                "a-5", taskId, "u1", "elsewhere", "no", NOW)))
                .isInstanceOf(RejectTargetNotAllowedException.class)
                .hasMessageContaining("elsewhere");
        verifyNoInteractions(workflows);
    }

    @Test
    void addAssigneeSendsThePositionAndAnchorSeat() {
        actions.addAssignee(
                instanceId,
                new AddAssigneeAction(
                        "a-6",
                        taskId,
                        "u1",
                        List.of(user("u2")),
                        cn.byteforce.seedj.workflow.spi.AddAssigneePosition.BEFORE,
                        "second pair of eyes",
                        NOW));

        verify(workflows)
                .addSign(
                        INSTANCE,
                        BRANCH,
                        List.of("u2"),
                        null,
                        null,
                        "second pair of eyes",
                        "a-6",
                        AddAssigneePosition.BEFORE,
                        SEAT,
                        "a-6");
        verify(sidecar).putAction(INSTANCE, "a-6", Map.of("operator", "u1", "position", "BEFORE"));
    }

    @Test
    void addAssigneeRejectsNonUserParticipants() {
        assertThatThrownBy(
                        () ->
                                actions.addAssignee(
                                        instanceId,
                                        new AddAssigneeAction(
                                                "a-7",
                                                taskId,
                                                "u1",
                                                List.of(
                                                        new ParticipantSpec(
                                                                ParticipantType.ROLE,
                                                                "role:hr",
                                                                Map.of())),
                                                cn.byteforce.seedj.workflow.spi.AddAssigneePosition
                                                        .CURRENT,
                                                "hr",
                                                NOW)))
                .isInstanceOf(WorkflowProviderException.class)
                .hasMessageContaining("USER");
        verifyNoInteractions(workflows);
    }

    @Test
    void removeAssigneeSendsTheRemovedPrincipalsAndTheAnchorSeat() {
        actions.removeAssignee(
                instanceId,
                new RemoveAssigneeAction("a-8", taskId, "u1", List.of("u2"), "left", NOW));

        verify(workflows).removeSign(INSTANCE, BRANCH, null, "left", "a-8", List.of("u2"), SEAT);
        verify(sidecar).markRemoved(INSTANCE, ROUND, List.of("u2"));
    }

    @Test
    void resumePassesTheNewDueInWithoutClipping() {
        actions.resume(
                instanceId,
                new ResumeWorkflowAction("a-9", "u1", Duration.ofMinutes(5), "extend", NOW));

        verify(workflows).resumeWorkflowOnBehalfOf(INSTANCE, 300_000, "extend", "a-9", "u1");
        verify(sidecar).putAction(INSTANCE, "a-9", Map.of("operator", "u1", "dueInMs", "300000"));
    }

    @Test
    void withdrawInstanceCancelsTheWorkflowAndKeepsTheRealTargetInTheSidecar() {
        actions.withdrawInstance(
                instanceId, new WithdrawInstanceAction("a-10", "u1", "start", "cancel it", NOW));

        verify(workflows).cancel(INSTANCE, "cancel it", false);
        verify(sidecar)
                .putAction(
                        INSTANCE,
                        "a-10",
                        Map.of(
                                "operator",
                                "u1",
                                "targetStep",
                                "start",
                                "withdrawInstance",
                                "true"));
    }

    @Test
    void terminateRunsAsTheConfiguredSignAdmin() {
        actions.terminate(instanceId, "stuck");

        verify(workflows)
                .terminateOnBehalfOf(
                        INSTANCE, "stuck", "seedj:terminate:" + INSTANCE, "sign-admin");
    }

    @Test
    void signalCarriesThePayloadAndTheIdempotencyKey() {
        actions.signal(instanceId, new SignalAction("a-11", "go", Map.of("k", "v"), NOW));

        verify(workflows).signal(eq(INSTANCE), eq("go"), any(byte[].class), eq("a-11"));
    }

    @Test
    void ccResolvesTheSeatOfTheNamedStep() {
        actions.cc(instanceId, new CcAction("a-12", "u1", List.of(user("u9")), NODE, "fyi", NOW));

        verify(workflows).cc(INSTANCE, SEAT, List.of("u9"), "fyi", "a-12");
    }

    @Test
    void actionsWithoutAPositionUseTheCallerServiceForTheSidecarExtras() {
        actions.approve(instanceId, new ApproveAction("a-13", taskId, "u1", "", Map.of(), NOW));

        assertThat(detail.tasks()).hasSize(1);
        verify(sidecar)
                .putAction(INSTANCE, "a-13", Map.of("operator", "u1", "targetStatus", "APPROVED"));
    }

    private static WorkflowDefinition definition(Set<String> rejectTargets) {
        HumanTaskSpec spec =
                new HumanTaskSpec(
                        List.of(user("u1")),
                        new AllApprove(),
                        null,
                        rejectTargets,
                        null,
                        Map.of(),
                        true,
                        Set.of(
                                cn.byteforce.seedj.workflow.spi.AddAssigneePosition.CURRENT,
                                cn.byteforce.seedj.workflow.spi.AddAssigneePosition.BEFORE,
                                cn.byteforce.seedj.workflow.spi.AddAssigneePosition.AFTER),
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        null,
                        Map.of());
        return new WorkflowDefinition(
                new WorkflowDefinitionId("contract", "actions"),
                new WorkflowDefinitionVersion(1, 0),
                List.of(
                        human("start"),
                        new StepDefinition(
                                NODE,
                                StepKind.HUMAN_TASK,
                                Duration.ofMinutes(5),
                                RetryPolicy.noRetry(),
                                List.of(),
                                Map.of(),
                                spec,
                                null),
                        new StepDefinition(
                                "done",
                                StepKind.ACTIVITY,
                                Duration.ofMinutes(5),
                                RetryPolicy.noRetry(),
                                List.of(),
                                Map.of())),
                Duration.ofMinutes(5),
                null,
                Map.of());
    }

    private static StepDefinition human(String name) {
        HumanTaskSpec spec =
                new HumanTaskSpec(
                        List.of(user("u1")),
                        new AllApprove(),
                        null,
                        Set.of(),
                        null,
                        Map.of(),
                        false,
                        Set.of(),
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        null,
                        Map.of());
        return new StepDefinition(
                name,
                StepKind.HUMAN_TASK,
                Duration.ofMinutes(5),
                RetryPolicy.noRetry(),
                List.of(),
                Map.of(),
                spec,
                null);
    }

    private static ParticipantSpec user(String value) {
        return new ParticipantSpec(ParticipantType.USER, value, Map.of());
    }

    private static ApprovalInstanceDetail detail() {
        ApprovalBranch start =
                new ApprovalBranch("branch-0", "start", 1, "closed", "all", "anyReject", List.of());
        ApprovalBranch review =
                new ApprovalBranch(BRANCH, NODE, 1, "open", "all", "anyReject", List.of(ROUND));
        ApprovalRound round =
                new ApprovalRound(ROUND, BRANCH, 1, "open", 1, 0, 0, "", NOW.toEpochMilli(), 0);
        ApprovalTask seat =
                new ApprovalTask(
                        SEAT,
                        BRANCH,
                        ROUND,
                        "approval",
                        "created",
                        "u1",
                        List.of("u1"),
                        NOW.plusSeconds(300).toEpochMilli(),
                        List.of(),
                        INSTANCE,
                        NODE,
                        "APPROVAL",
                        NOW.toEpochMilli(),
                        "",
                        "",
                        "",
                        List.of(
                                new ApprovalAssignment(
                                        "u1", "pending", "", null, NOW.toEpochMilli(), 0)));
        return new ApprovalInstanceDetail(
                INSTANCE,
                "SUSPENDED",
                "starter",
                1,
                "actions",
                "test:workflow:contract",
                "1.0",
                List.of(start, review),
                List.of(seat),
                List.of(round),
                List.of());
    }
}
