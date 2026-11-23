package cn.byteforce.seedj.infra.coord.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;
import cn.byteforce.coord.sdk.workflow.ApprovalAssignment;
import cn.byteforce.coord.sdk.workflow.ApprovalBranch;
import cn.byteforce.coord.sdk.workflow.ApprovalHistoryEvent;
import cn.byteforce.coord.sdk.workflow.ApprovalInstanceDetail;
import cn.byteforce.coord.sdk.workflow.ApprovalRound;
import cn.byteforce.coord.sdk.workflow.ApprovalTask;
import cn.byteforce.coord.sdk.workflow.ApprovalVote;
import cn.byteforce.coord.sdk.workflow.InstancePage;
import cn.byteforce.coord.sdk.workflow.TaskGroup;
import cn.byteforce.coord.sdk.workflow.TaskGroupPage;
import cn.byteforce.coord.sdk.workflow.WorkflowClient;
import cn.byteforce.coord.sdk.workflow.WorkflowFault;
import cn.byteforce.coord.sdk.workflow.WorkflowInstanceSummary;
import cn.byteforce.coord.sdk.workflow.WorkflowState;
import cn.byteforce.seedj.infra.coord.workflow.CoordWorkflowSidecar.ServerClock;
import cn.byteforce.seedj.workflow.spi.ActionDecision;
import cn.byteforce.seedj.workflow.spi.AssignmentStatus;
import cn.byteforce.seedj.workflow.spi.HumanTask;
import cn.byteforce.seedj.workflow.spi.HumanTaskKind;
import cn.byteforce.seedj.workflow.spi.HumanTaskStatus;
import cn.byteforce.seedj.workflow.spi.TaskId;
import cn.byteforce.seedj.workflow.spi.TaskQuery;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionId;
import cn.byteforce.seedj.workflow.spi.WorkflowHistoryEvent;
import cn.byteforce.seedj.workflow.spi.WorkflowInstance;
import cn.byteforce.seedj.workflow.spi.WorkflowInstanceId;
import cn.byteforce.seedj.workflow.spi.WorkflowNotFoundException;
import cn.byteforce.seedj.workflow.spi.WorkflowSearchQuery;
import cn.byteforce.seedj.workflow.spi.WorkflowStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T7/T8：读面映射表（状态、任务、assignment、事件属性、search 双路径），全部离线。 */
class CoordWorkflowReadsTest {

    private static final String INSTANCE = "wf-reads";
    private static final String ROUND = INSTANCE + ":round-0001";
    private static final long T0 = 1_700_000_000_000L;

    private final WorkflowClient workflows = mock(WorkflowClient.class);
    private final CoordWorkflowDefinitions definitions = mock(CoordWorkflowDefinitions.class);
    private final CoordWorkflowSidecar sidecar = mock(CoordWorkflowSidecar.class);
    private final CoordWorkflowIds ids = new CoordWorkflowIds("test");
    private final ServerClock clock = new ServerClock();
    private final CoordWorkflowReads reads =
            new CoordWorkflowReads(workflows, ids, definitions, sidecar, clock);
    private final WorkflowInstanceId instanceId = new WorkflowInstanceId(INSTANCE);

    @BeforeEach
    void defaults() {
        when(definitions.definitionIdOf("test:workflow:contract", "approval"))
                .thenReturn(new WorkflowDefinitionId("contract", "approval"));
        when(definitions.find(any(), any())).thenReturn(java.util.Optional.empty());
        when(workflows.getInstanceDetail(INSTANCE))
                .thenReturn(detail("open", seat("u1", List.of())));
        when(workflows.listTaskGroups(any(), anyInt(), anyString()))
                .thenReturn(new TaskGroupPage(List.of(), ""));
    }

    // ──── 实例状态表 ────

    @Test
    void describeMapsSuspendedToRunningAndExposesInputAndPendingRounds() {
        when(workflows.getStatus(INSTANCE))
                .thenReturn(status(WorkflowState.SUSPENDED, "review", ""));
        when(workflows.listTaskGroups(any(), anyInt(), anyString()))
                .thenReturn(new TaskGroupPage(List.of(group("open", seat("u1", List.of()))), ""));

        WorkflowInstance instance = reads.describe(instanceId);

        assertThat(instance.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(instance.currentStep()).isEqualTo("review");
        assertThat(instance.pendingTaskIds()).containsExactly(new TaskId(ROUND));
        assertThat(instance.startedBy()).isEqualTo("starter");
        assertThat(instance.startedAt()).isEqualTo(Instant.ofEpochMilli(T0));
        assertThat(instance.variables())
                .containsEntry("orderId", "o-1")
                .doesNotContainKey("_internal");
        assertThat(instance.completedAt()).isNull();
    }

    @Test
    void describePrefersTheOpenBranchNodeOverAStaleRuntimeName() {
        when(workflows.getStatus(INSTANCE))
                .thenReturn(status(WorkflowState.SUSPENDED, "start", ""));
        when(workflows.getInstanceDetail(INSTANCE))
                .thenReturn(detailWithBranch("review", "open", seat("u1", List.of())));

        assertThat(reads.describe(instanceId).currentStep()).isEqualTo("review");
    }

    @Test
    void describeMapsTerminalInstances() {
        when(workflows.getStatus(INSTANCE))
                .thenReturn(status(WorkflowState.COMPLETED, "", "COMPLETED"));

        WorkflowInstance instance = reads.describe(instanceId);

        assertThat(instance.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(instance.currentStep()).isNull();
        assertThat(instance.completedAt()).isEqualTo(Instant.ofEpochMilli(T0 + 1_000));
    }

    @Test
    void coordStateTableMatchesTheSeedjVocabulary() {
        assertThat(CoordWorkflowReads.status((WorkflowState) null, ""))
                .isEqualTo(WorkflowStatus.PENDING);
        assertThat(CoordWorkflowReads.status(WorkflowState.PENDING, ""))
                .isEqualTo(WorkflowStatus.PENDING);
        assertThat(CoordWorkflowReads.status(WorkflowState.RUNNING, ""))
                .isEqualTo(WorkflowStatus.RUNNING);
        assertThat(CoordWorkflowReads.status(WorkflowState.WAITING, ""))
                .isEqualTo(WorkflowStatus.WAITING);
        assertThat(CoordWorkflowReads.status(WorkflowState.SUSPENDED, ""))
                .isEqualTo(WorkflowStatus.RUNNING);
        assertThat(CoordWorkflowReads.status(WorkflowState.COMPLETED, ""))
                .isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(CoordWorkflowReads.status(WorkflowState.COMPENSATED, ""))
                .isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(CoordWorkflowReads.status(WorkflowState.CANCELLED, ""))
                .isEqualTo(WorkflowStatus.CANCELLED);
        assertThat(CoordWorkflowReads.status(WorkflowState.FAILED, ""))
                .isEqualTo(WorkflowStatus.FAILED);
        assertThat(CoordWorkflowReads.status(WorkflowState.FAULTED, "TERMINATED"))
                .isEqualTo(WorkflowStatus.TERMINATED);
        assertThat(
                        CoordWorkflowReads.status(
                                WorkflowState.FAULTED,
                                "TERMINATED",
                                new WorkflowFault("faulted", "Faulted", "boom", 500, INSTANCE)))
                .isEqualTo(WorkflowStatus.FAILED);
        assertThat(
                        CoordWorkflowReads.status(
                                WorkflowState.FAULTED,
                                "TERMINATED",
                                new WorkflowFault(
                                        "terminated",
                                        "Terminated by admin",
                                        "stop",
                                        409,
                                        INSTANCE)))
                .isEqualTo(WorkflowStatus.TERMINATED);
    }

    @Test
    void unknownInstancesSurfaceAsNotFound() {
        when(workflows.getStatus(INSTANCE))
                .thenThrow(new CoordException(ErrorCode.NOT_FOUND, "missing"));

        assertThatThrownBy(() -> reads.describe(instanceId))
                .isInstanceOf(WorkflowNotFoundException.class);
    }

    // ──── 任务/席位/assignment 表 ────

    @Test
    void describeTaskMapsSeatsAndVotesIntoAssignments() {
        ApprovalVote vote =
                new ApprovalVote(ROUND, "approve", "u2", "u3", "ok", T0 + 5_000, "svc-ai");
        ApprovalTask seat =
                seat(
                        "u1",
                        List.of(
                                new ApprovalAssignment("u1", "pending", "", null, T0, 0),
                                new ApprovalAssignment(
                                        "u2", "completed", "", vote, T0, T0 + 5_000)));
        when(workflows.getInstanceDetail(INSTANCE)).thenReturn(detail("open", seat));
        when(workflows.listTaskGroups(any(), anyInt(), anyString()))
                .thenReturn(new TaskGroupPage(List.of(group("open", seat)), ""));

        HumanTask task = reads.describeTask(new TaskId(ROUND)).orElseThrow();

        assertThat(task.status()).isEqualTo(HumanTaskStatus.PENDING);
        assertThat(task.kind()).isEqualTo(HumanTaskKind.ORIGINAL);
        assertThat(task.stepName()).isEqualTo("review");
        assertThat(task.dueAt()).isEqualTo(Instant.ofEpochMilli(T0 + 300_000));
        assertThat(task.assignments()).hasSize(2);
        assertThat(task.assignments().get(0).status()).isEqualTo(AssignmentStatus.PENDING);
        assertThat(task.assignments().get(1).status()).isEqualTo(AssignmentStatus.COMPLETED);
        assertThat(task.assignments().get(1).record().decision()).isEqualTo(ActionDecision.APPROVE);
        assertThat(task.assignments().get(1).record().onBehalfOf()).isEqualTo("u3");
        assertThat(task.assignments().get(1).record().extra())
                .containsEntry("actingService", "svc-ai");
    }

    @Test
    void scheduledRoundsStayHiddenUntilAnAfterAddSignIsDeferred() {
        ApprovalTask spawned = derivedSeat("u2", "AFTER_ADDED", "action-1", "seat-1", List.of());
        when(workflows.getInstanceDetail(INSTANCE)).thenReturn(detail("scheduled", spawned));
        when(workflows.listTaskGroups(any(), anyInt(), anyString()))
                .thenReturn(new TaskGroupPage(List.of(group("scheduled", spawned)), ""));

        assertThat(reads.describeTask(new TaskId(ROUND))).isEmpty();
        assertThat(reads.listTasks(query().build())).isEmpty();

        when(sidecar.actions(INSTANCE))
                .thenReturn(Map.of("action-1", Map.of("completedAnchorTaskId", ROUND)));

        HumanTask task = reads.describeTask(new TaskId(ROUND)).orElseThrow();

        assertThat(task.status()).isEqualTo(HumanTaskStatus.PENDING);
        assertThat(task.kind()).isEqualTo(HumanTaskKind.AFTER_ADDED);
        assertThat(task.predecessorTaskId()).isEqualTo(new TaskId(ROUND));
    }

    @Test
    void withdrawnSeatsRenderWithdrawnAndTheInstanceWithdrawReason() {
        ApprovalTask seat =
                seat(
                        "u1",
                        List.of(
                                new ApprovalAssignment(
                                        "u1",
                                        "cancelled",
                                        "TASK_CANCELLED",
                                        null,
                                        T0,
                                        T0 + 1_000)));
        when(workflows.getInstanceDetail(INSTANCE))
                .thenReturn(detailWithBranchAndStatus("superseded", "review", seat));
        when(workflows.listTaskGroups(any(), anyInt(), anyString()))
                .thenReturn(new TaskGroupPage(List.of(group("superseded", seat)), ""));
        when(sidecar.actions(INSTANCE))
                .thenReturn(Map.of("w-1", Map.of("withdrawInstance", "true")));

        HumanTask task = reads.describeTask(new TaskId(ROUND)).orElseThrow();

        assertThat(task.status()).isEqualTo(HumanTaskStatus.WITHDRAWN);
        assertThat(task.assignments().get(0).status()).isEqualTo(AssignmentStatus.CANCELLED);
        assertThat(task.assignments().get(0).resolvedReason()).isEqualTo("WITHDRAWN");
    }

    @Test
    void removedAssigneesAreMarkedWithTheSidecarReason() {
        ApprovalTask seat =
                seat(
                        "u1",
                        List.of(
                                new ApprovalAssignment(
                                        "u1", "cancelled", "TASK_CANCELLED", null, T0, T0)));
        when(workflows.getInstanceDetail(INSTANCE)).thenReturn(detail("open", seat));
        when(workflows.listTaskGroups(any(), anyInt(), anyString()))
                .thenReturn(new TaskGroupPage(List.of(group("open", seat)), ""));
        when(sidecar.removed(INSTANCE, ROUND, "u1")).thenReturn(true);

        HumanTask task = reads.describeTask(new TaskId(ROUND)).orElseThrow();

        assertThat(task.assignments().get(0).resolvedReason()).isEqualTo("REMOVED");
    }

    @Test
    void delegationExpiryRestoresTheOriginalAssignee() {
        ApprovalTask seat =
                seat(
                        "u1",
                        List.of(
                                new ApprovalAssignment(
                                        "u1", "cancelled", "DELEGATION_EXPIRED", null, T0, T0)));
        when(workflows.getInstanceDetail(INSTANCE)).thenReturn(detail("open", seat));
        when(workflows.listTaskGroups(any(), anyInt(), anyString()))
                .thenReturn(new TaskGroupPage(List.of(group("open", seat)), ""));

        HumanTask task = reads.describeTask(new TaskId(ROUND)).orElseThrow();

        assertThat(task.assignments().get(0).status()).isEqualTo(AssignmentStatus.PENDING);
        assertThat(task.assignments().get(0).resolvedReason()).isNull();
    }

    @Test
    void listTasksAppliesPendingForStatusAndCursorFilters() {
        ApprovalTask seat =
                seat("u1", List.of(new ApprovalAssignment("u1", "pending", "", null, T0, 0)));
        when(workflows.getInstanceDetail(INSTANCE)).thenReturn(detail("open", seat));
        when(workflows.listTaskGroups(any(), anyInt(), anyString()))
                .thenReturn(new TaskGroupPage(List.of(group("open", seat)), ""));

        assertThat(reads.listTasks(query().build())).hasSize(1);
        assertThat(reads.listTasks(query().pendingFor("u9").build())).isEmpty();
        assertThat(reads.listTasks(query().status(HumanTaskStatus.COMPLETED).build())).isEmpty();
        assertThat(reads.listTasks(query().cursor("1").build())).isEmpty();
    }

    // ──── 历史与事件属性 ────

    @Test
    void historyNormalizesReasonsJoinsRejectActionsAndMapsSeatsToRounds() {
        when(workflows.history(INSTANCE))
                .thenReturn(
                        List.of(
                                new ApprovalHistoryEvent(
                                        INSTANCE,
                                        "e-1",
                                        "TASK_REJECTED",
                                        "review",
                                        "seat-1",
                                        "a-1",
                                        T0,
                                        "{\"reason\":\"on_exhausted\",\"rejectActionIds\":[\"a-1\",\"a-2\"]}"),
                                new ApprovalHistoryEvent(
                                        INSTANCE,
                                        "e-2",
                                        "TASK_URGED",
                                        "review",
                                        "seat-1",
                                        "a-2",
                                        T0 + 10,
                                        "{\"targetStatus\":\"open\"}")));

        List<WorkflowHistoryEvent> history = reads.history(instanceId);

        assertThat(history.get(0).eventType()).isEqualTo("TASK_REJECTED");
        assertThat(history.get(0).taskId()).isEqualTo(ROUND);
        assertThat(history.get(0).attributes())
                .containsEntry("reason", "SIGN_COMPLETION_UNSATISFIABLE")
                .containsEntry("rejectActionIds", "a-1,a-2");
        assertThat(history.get(1).attributes()).containsEntry("targetStatus", "OPEN");
    }

    @Test
    void historySynthesizesTheInstanceWithdrawVocabularyFromTheSidecar() {
        when(workflows.history(INSTANCE)).thenReturn(List.of());
        when(sidecar.actions(INSTANCE))
                .thenReturn(
                        Map.of(
                                "w-1",
                                Map.of(
                                        "withdrawInstance",
                                        "true",
                                        "operator",
                                        "u1",
                                        "targetStep",
                                        "start")));

        List<WorkflowHistoryEvent> history = reads.history(instanceId);

        assertThat(history)
                .extracting(WorkflowHistoryEvent::eventType)
                .containsExactly("TASK_WITHDRAWN", "INSTANCE_WITHDRAWN", "INSTANCE_ROLLED_BACK");
        assertThat(history.get(2).stepName()).isEqualTo("start");
        assertThat(history.get(1).attributes()).containsEntry("reason", "INSTANCE_WITHDRAWN");
    }

    // ──── search 双路径 ────

    @Test
    void searchPassesTheSingleStatusFastPathThroughAndSkipsTheCursorOffset() {
        when(workflows.listInstances(
                        "test:workflow:contract",
                        "approval",
                        List.of("RUNNING", "SUSPENDED"),
                        0L,
                        0L,
                        2,
                        ""))
                .thenReturn(
                        new InstancePage(
                                List.of(
                                        summary("wf-1", "RUNNING"),
                                        summary("wf-2", "SUSPENDED"),
                                        summary("wf-3", "COMPLETED")),
                                ""));

        List<WorkflowInstance> found =
                reads.search(
                        new WorkflowSearchQuery(
                                new WorkflowDefinitionId("contract", "approval"),
                                WorkflowStatus.RUNNING,
                                null,
                                null,
                                2,
                                "1"));

        assertThat(found).extracting(instance -> instance.id().value()).containsExactly("wf-2");
        assertThat(found.get(0).status()).isEqualTo(WorkflowStatus.RUNNING);
        verify(workflows)
                .listInstances(
                        "test:workflow:contract",
                        "approval",
                        List.of("RUNNING", "SUSPENDED"),
                        0L,
                        0L,
                        2,
                        "");
    }

    @Test
    void searchDropsInstancesWhoseMappedStatusDoesNotMatch() {
        when(workflows.listInstances(
                        any(), any(), anyList(), anyLong(), anyLong(), anyInt(), anyString()))
                .thenReturn(
                        new InstancePage(
                                List.of(
                                        summary("wf-failed", "FAULTED"),
                                        summary("wf-done", "COMPLETED")),
                                ""));

        List<WorkflowInstance> found =
                reads.search(
                        new WorkflowSearchQuery(null, WorkflowStatus.FAILED, null, null, 10, null));

        assertThat(found)
                .extracting(instance -> instance.id().value())
                .containsExactly("wf-failed");
        assertThat(found.get(0).status()).isEqualTo(WorkflowStatus.FAILED);
    }

    // ──── fixtures ────

    private static cn.byteforce.coord.sdk.workflow.WorkflowStatus status(
            WorkflowState state, String currentNode, String approvalStatus) {
        return new cn.byteforce.coord.sdk.workflow.WorkflowStatus(
                INSTANCE,
                state,
                0,
                null,
                null,
                "approval",
                "{\"orderId\":\"o-1\",\"_signal\":{\"payload\":{\"sig\":\"v\"}},\"_internal\":\"x\"}"
                        .getBytes(StandardCharsets.UTF_8),
                T0,
                T0 + 1_000,
                List.of(),
                currentNode,
                null,
                "test:workflow:contract",
                "1.0",
                null,
                approvalStatus);
    }

    private static ApprovalInstanceDetail detail(String roundStatus, ApprovalTask seat) {
        return detailWithBranchAndStatus(roundStatus, "review", seat);
    }

    private static ApprovalInstanceDetail detailWithBranch(
            String node, String branchStatus, ApprovalTask seat) {
        return detailWithBranchAndStatus("open", node, branchStatus, seat);
    }

    private static ApprovalInstanceDetail detailWithBranchAndStatus(
            String roundStatus, String node, ApprovalTask seat) {
        return detailWithBranchAndStatus(roundStatus, node, "open", seat);
    }

    private static ApprovalInstanceDetail detailWithBranchAndStatus(
            String roundStatus, String node, String branchStatus, ApprovalTask seat) {
        ApprovalBranch branch =
                new ApprovalBranch(
                        "branch-1", node, 1, branchStatus, "all", "anyReject", List.of(ROUND));
        ApprovalRound round =
                new ApprovalRound(ROUND, "branch-1", 1, roundStatus, 1, 0, 0, "", T0, 0);
        return new ApprovalInstanceDetail(
                INSTANCE,
                "SUSPENDED",
                "starter",
                1,
                "approval",
                "test:workflow:contract",
                "1.0",
                List.of(branch),
                List.of(seat),
                List.of(round),
                List.of());
    }

    private static ApprovalTask seat(String assignee, List<ApprovalAssignment> assignments) {
        return new ApprovalTask(
                "seat-1",
                "branch-1",
                ROUND,
                "approval",
                "created",
                assignee,
                List.of(assignee),
                T0 + 300_000,
                List.of(),
                INSTANCE,
                "review",
                "APPROVAL",
                T0,
                "",
                "",
                "",
                assignments);
    }

    private static ApprovalTask derivedSeat(
            String assignee,
            String origin,
            String actionId,
            String predecessor,
            List<ApprovalAssignment> assignments) {
        return new ApprovalTask(
                "seat-2",
                "branch-1",
                ROUND,
                "approval",
                "created",
                assignee,
                List.of(assignee),
                T0 + 300_000,
                List.of(),
                INSTANCE,
                "review",
                origin,
                T0,
                predecessor,
                "",
                actionId,
                assignments);
    }

    private static TaskGroup group(String status, ApprovalTask seat) {
        return new TaskGroup(
                "g-1",
                INSTANCE,
                "branch-1",
                ROUND,
                "review",
                status,
                "APPROVAL",
                List.of(seat),
                List.of(),
                T0,
                T0 + 300_000);
    }

    private static WorkflowInstanceSummary summary(String instanceId, String state) {
        return new WorkflowInstanceSummary(
                instanceId,
                instanceId,
                state,
                T0,
                T0 + 100,
                "approval",
                "test",
                null,
                "{\"orderId\":\"o-1\"}".getBytes(StandardCharsets.UTF_8),
                "test:workflow:contract",
                "1.0",
                "starter",
                0);
    }

    private static QueryBuilder query() {
        return new QueryBuilder();
    }

    private static final class QueryBuilder {
        private String pendingFor;
        private HumanTaskStatus status;
        private String cursor;

        QueryBuilder pendingFor(String value) {
            this.pendingFor = value;
            return this;
        }

        QueryBuilder status(HumanTaskStatus value) {
            this.status = value;
            return this;
        }

        QueryBuilder cursor(String value) {
            this.cursor = value;
            return this;
        }

        TaskQuery build() {
            return new TaskQuery(
                    new WorkflowInstanceId(INSTANCE),
                    pendingFor,
                    null,
                    List.of(),
                    null,
                    status,
                    null,
                    10,
                    cursor);
        }
    }
}
