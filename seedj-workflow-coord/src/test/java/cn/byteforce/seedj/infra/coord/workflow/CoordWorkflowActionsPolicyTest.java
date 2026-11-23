package cn.byteforce.seedj.infra.coord.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.byteforce.coord.sdk.kv.KvClient;
import cn.byteforce.coord.sdk.workflow.ApprovalAssignment;
import cn.byteforce.coord.sdk.workflow.ApprovalBranch;
import cn.byteforce.coord.sdk.workflow.ApprovalInstanceDetail;
import cn.byteforce.coord.sdk.workflow.ApprovalRound;
import cn.byteforce.coord.sdk.workflow.ApprovalTask;
import cn.byteforce.coord.sdk.workflow.WorkflowClient;
import cn.byteforce.seedj.infra.coord.workflow.CoordWorkflowSidecar.ServerClock;
import cn.byteforce.seedj.workflow.spi.AddAssigneeAction;
import cn.byteforce.seedj.workflow.spi.AddAssigneePosition;
import cn.byteforce.seedj.workflow.spi.AllApprove;
import cn.byteforce.seedj.workflow.spi.CcAction;
import cn.byteforce.seedj.workflow.spi.DelegateAction;
import cn.byteforce.seedj.workflow.spi.HumanTaskSpec;
import cn.byteforce.seedj.workflow.spi.ParticipantSpec;
import cn.byteforce.seedj.workflow.spi.ParticipantType;
import cn.byteforce.seedj.workflow.spi.StepDefinition;
import cn.byteforce.seedj.workflow.spi.StepKind;
import cn.byteforce.seedj.workflow.spi.TaskId;
import cn.byteforce.seedj.workflow.spi.TransferAction;
import cn.byteforce.seedj.workflow.spi.UrgeAction;
import cn.byteforce.seedj.workflow.spi.WithdrawAction;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinition;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionId;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionVersion;
import cn.byteforce.seedj.workflow.spi.WorkflowInstanceId;
import cn.byteforce.seedj.workflow.spi.WorkflowProviderException;
import cn.byteforce.seedj.workflow.spi.WorkflowStateConflictException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T10/T11 静态开关：定义不允许的动作必须在调用 coord 之前 fail-closed。 */
class CoordWorkflowActionsPolicyTest {

    private static final String INSTANCE = "wf-policy";
    private static final String ROUND = INSTANCE + ":round-0001";
    private static final String NODE = "review";
    private static final Instant NOW = Instant.parse("2026-11-22T08:00:00Z");

    private final WorkflowClient workflows = mock(WorkflowClient.class);
    private final CoordWorkflowReads reads = mock(CoordWorkflowReads.class);
    private final KvClient kv = mock(KvClient.class);
    private final WorkflowInstanceId instanceId = new WorkflowInstanceId(INSTANCE);
    private final TaskId taskId = new TaskId(ROUND);

    private CoordWorkflowActions actions;
    private ApprovalInstanceDetail detail;

    @BeforeEach
    void setUp() {
        actions =
                new CoordWorkflowActions(
                        workflows,
                        reads,
                        new CoordWorkflowSidecar(kv, "test"),
                        new ServerClock(),
                        "sign-admin");
        detail = detail();
        when(reads.detailOrNull(INSTANCE)).thenReturn(detail);
    }

    @Test
    void transferIsRejectedWhenTheStepDisallowsIt() {
        stubDefinition(spec(false, false, false, false, false));

        assertThatThrownBy(
                        () ->
                                actions.transfer(
                                        instanceId,
                                        new TransferAction(
                                                "a-1", taskId, "u1", user("u2"), "hand over", NOW)))
                .isInstanceOf(WorkflowStateConflictException.class)
                .hasMessageContaining("transfer is not allowed");
        verifyNoInteractions(workflows);
    }

    @Test
    void delegateIsRejectedWhenTheStepDisallowsIt() {
        stubDefinition(spec(false, false, false, false, false));

        assertThatThrownBy(
                        () ->
                                actions.delegate(
                                        instanceId,
                                        new DelegateAction(
                                                "a-2",
                                                taskId,
                                                "u1",
                                                user("u2"),
                                                Duration.ofHours(1),
                                                "vacation",
                                                NOW)))
                .isInstanceOf(WorkflowStateConflictException.class)
                .hasMessageContaining("delegate is not allowed");
        verifyNoInteractions(workflows);
    }

    @Test
    void withdrawIsRejectedWhenTheStepDisallowsIt() {
        stubDefinition(spec(false, false, false, false, false));

        assertThatThrownBy(
                        () ->
                                actions.withdrawTask(
                                        instanceId,
                                        new WithdrawAction(
                                                "a-3", taskId, "u1", NODE, "changed my mind", NOW)))
                .isInstanceOf(WorkflowStateConflictException.class)
                .hasMessageContaining("withdraw is not allowed");
        verifyNoInteractions(workflows);
    }

    @Test
    void urgeIsRejectedWhenTheStepDisallowsIt() {
        stubDefinition(spec(false, false, false, false, false));

        assertThatThrownBy(
                        () ->
                                actions.urge(
                                        instanceId,
                                        new UrgeAction("a-4", taskId, "u1", "please", NOW)))
                .isInstanceOf(WorkflowStateConflictException.class)
                .hasMessageContaining("urge is not allowed");
        verifyNoInteractions(workflows);
    }

    @Test
    void addAssigneeIsRejectedWhenTheStepDisallowsThePosition() {
        stubDefinition(spec(false, false, false, false, false));

        assertThatThrownBy(
                        () ->
                                actions.addAssignee(
                                        instanceId,
                                        new AddAssigneeAction(
                                                "a-5",
                                                taskId,
                                                "u1",
                                                List.of(user("u2")),
                                                AddAssigneePosition.AFTER,
                                                "second pair of eyes",
                                                NOW)))
                .isInstanceOf(WorkflowStateConflictException.class)
                .hasMessageContaining("add-assignee position");
        verifyNoInteractions(workflows);
    }

    @Test
    void ccIsRejectedWhenTheStepDisallowsIt() {
        stubDefinition(spec(false, false, false, false, false));

        assertThatThrownBy(
                        () ->
                                actions.cc(
                                        instanceId,
                                        new CcAction(
                                                "a-6",
                                                "u1",
                                                List.of(user("u9")),
                                                NODE,
                                                "fyi",
                                                NOW)))
                .isInstanceOf(WorkflowStateConflictException.class)
                .hasMessageContaining("cc is not allowed");
        verifyNoInteractions(workflows);
    }

    @Test
    void nonUserTransferRecipientFailsClosedWithIssueReference() {
        stubDefinition(spec(true, false, false, false, false));

        assertThatThrownBy(
                        () ->
                                actions.transfer(
                                        instanceId,
                                        new TransferAction(
                                                "a-7",
                                                taskId,
                                                "u1",
                                                new ParticipantSpec(
                                                        ParticipantType.DEPARTMENT,
                                                        "dept:hr",
                                                        Map.of()),
                                                "hand over",
                                                NOW)))
                .isInstanceOf(WorkflowProviderException.class)
                .hasMessageContaining("USER")
                .hasMessageContaining("coord#454");
        verifyNoInteractions(workflows);
    }

    @Test
    void nonUserDelegateRecipientFailsClosedWithIssueReference() {
        stubDefinition(spec(false, true, false, false, false));

        assertThatThrownBy(
                        () ->
                                actions.delegate(
                                        instanceId,
                                        new DelegateAction(
                                                "a-8",
                                                taskId,
                                                "u1",
                                                new ParticipantSpec(
                                                        ParticipantType.GROUP,
                                                        "group:ops",
                                                        Map.of()),
                                                Duration.ofHours(1),
                                                "vacation",
                                                NOW)))
                .isInstanceOf(WorkflowProviderException.class)
                .hasMessageContaining("USER")
                .hasMessageContaining("coord#454");
        verifyNoInteractions(workflows);
    }

    @Test
    void allowedFlagsLetTheActionReachCoord() {
        stubDefinition(spec(true, true, true, true, true));

        actions.transfer(
                instanceId, new TransferAction("a-9", taskId, "u1", user("u2"), "hand over", NOW));

        assertThat(detail.rounds()).hasSize(1);
    }

    private void stubDefinition(HumanTaskSpec spec) {
        WorkflowDefinition definition =
                new WorkflowDefinition(
                        new WorkflowDefinitionId("contract", "policy"),
                        new WorkflowDefinitionVersion(1, 0),
                        List.of(
                                new StepDefinition(
                                        NODE,
                                        StepKind.HUMAN_TASK,
                                        Duration.ofMinutes(5),
                                        cn.byteforce.seedj.workflow.spi.RetryPolicy.noRetry(),
                                        List.of(),
                                        Map.of(),
                                        spec,
                                        null)),
                        Duration.ofMinutes(5),
                        null,
                        Map.of());
        when(reads.definitionOf(detail)).thenReturn(definition);
    }

    private static HumanTaskSpec spec(
            boolean allowTransfer,
            boolean allowDelegate,
            boolean allowWithdraw,
            boolean allowUrge,
            boolean allowCc) {
        return new HumanTaskSpec(
                List.of(user("u1")),
                new AllApprove(),
                null,
                Set.of(),
                null,
                Map.of(),
                false,
                Set.<AddAssigneePosition>of(),
                false,
                allowTransfer,
                allowDelegate,
                allowWithdraw,
                allowUrge,
                allowCc,
                null,
                Map.of());
    }

    private static ParticipantSpec user(String value) {
        return new ParticipantSpec(ParticipantType.USER, value, Map.of());
    }

    private static ApprovalInstanceDetail detail() {
        ApprovalBranch branch =
                new ApprovalBranch("branch-1", NODE, 1, "open", "all", "anyReject", List.of(ROUND));
        ApprovalRound round =
                new ApprovalRound(ROUND, "branch-1", 1, "open", 1, 0, 0, "", NOW.toEpochMilli(), 0);
        ApprovalTask seat =
                new ApprovalTask(
                        "seat-1",
                        "branch-1",
                        ROUND,
                        "approval",
                        "created",
                        "u1",
                        List.of("u1"),
                        0,
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
                "policy",
                "test:workflow:contract",
                "1.0",
                List.of(branch),
                List.of(seat),
                List.of(round),
                List.of());
    }
}
