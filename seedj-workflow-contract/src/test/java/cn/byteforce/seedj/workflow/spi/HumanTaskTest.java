package cn.byteforce.seedj.workflow.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 人工任务快照：必填字段、集合归一化，以及按 assignment 状态派生的视图。 */
class HumanTaskTest {

    private static final Instant NOW = Instant.parse("2026-02-01T08:00:00Z");

    private static ParticipantSpec user(String value) {
        return new ParticipantSpec(ParticipantType.USER, value, Map.of());
    }

    private static ApproveRecord approve(String assignee) {
        return new ApproveRecord(assignee, null, ActionDecision.APPROVE, "ok", NOW, Map.of());
    }

    private static HumanTask task(List<TaskAssignment> assignments) {
        return new HumanTask(
                new TaskId("t-1"),
                new WorkflowInstanceId("i-1"),
                "review",
                HumanTaskKind.ORIGINAL,
                HumanTaskStatus.PENDING,
                assignments,
                null,
                null,
                null,
                null,
                NOW,
                NOW,
                null,
                null,
                null);
    }

    @Test
    @DisplayName("构造：必填字段与非空集合")
    void requiresCoreFields() {
        HumanTask task = task(null);

        assertThat(task.id()).isEqualTo(new TaskId("t-1"));
        assertThat(task.instanceId()).isEqualTo(new WorkflowInstanceId("i-1"));
        assertThat(task.stepName()).isEqualTo("review");
        assertThat(task.kind()).isEqualTo(HumanTaskKind.ORIGINAL);
        assertThat(task.status()).isEqualTo(HumanTaskStatus.PENDING);
        assertThat(task.assignments()).isEmpty();
        assertThat(task.formData()).isEmpty();
        assertThat(task.metadata()).isEmpty();
        assertThat(task.createdAt()).isEqualTo(NOW);
        assertThat(task.updatedAt()).isEqualTo(NOW);
        assertThat(task.completedAt()).isNull();
        assertThat(task.dueAt()).isNull();
        assertThat(task.predecessorTaskId()).isNull();
        assertThat(task.successorTaskId()).isNull();
        assertThat(task.spawnedByActionId()).isNull();

        assertThatThrownBy(
                        () ->
                                new HumanTask(
                                        null,
                                        new WorkflowInstanceId("i-1"),
                                        "review",
                                        HumanTaskKind.ORIGINAL,
                                        HumanTaskStatus.PENDING,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        NOW,
                                        NOW,
                                        null,
                                        null,
                                        null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new HumanTask(
                                        new TaskId("t-1"),
                                        null,
                                        "review",
                                        HumanTaskKind.ORIGINAL,
                                        HumanTaskStatus.PENDING,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        NOW,
                                        NOW,
                                        null,
                                        null,
                                        null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new HumanTask(
                                        new TaskId("t-1"),
                                        new WorkflowInstanceId("i-1"),
                                        null,
                                        HumanTaskKind.ORIGINAL,
                                        HumanTaskStatus.PENDING,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        NOW,
                                        NOW,
                                        null,
                                        null,
                                        null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new HumanTask(
                                        new TaskId("t-1"),
                                        new WorkflowInstanceId("i-1"),
                                        "review",
                                        null,
                                        HumanTaskStatus.PENDING,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        NOW,
                                        NOW,
                                        null,
                                        null,
                                        null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new HumanTask(
                                        new TaskId("t-1"),
                                        new WorkflowInstanceId("i-1"),
                                        "review",
                                        HumanTaskKind.ORIGINAL,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        NOW,
                                        NOW,
                                        null,
                                        null,
                                        null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("派生视图：PENDING / SUSPENDED / COMPLETED 各自对应一类参与者")
    void derivesAssigneeViews() {
        TaskAssignment pending =
                new TaskAssignment(user("u-1"), AssignmentStatus.PENDING, null, NOW, null, null);
        TaskAssignment suspended =
                new TaskAssignment(user("u-2"), AssignmentStatus.SUSPENDED, null, NOW, null, null);
        TaskAssignment completed =
                new TaskAssignment(
                        user("u-3"),
                        AssignmentStatus.COMPLETED,
                        approve("u-3"),
                        NOW,
                        NOW,
                        "approved");
        TaskAssignment transferred =
                new TaskAssignment(user("u-4"), AssignmentStatus.TRANSFERRED, null, NOW, NOW, null);

        HumanTask task = task(List.of(pending, suspended, completed, transferred));

        assertThat(task.assignments()).hasSize(4);
        assertThat(task.pendingAssignees()).containsExactly(user("u-1"));
        assertThat(task.suspendedAssignees()).containsExactly(user("u-2"));
        assertThat(task.approveRecords()).containsExactly(approve("u-3"));
    }

    @Test
    @DisplayName("formData 与 metadata 不可变")
    void collectionsAreImmutable() {
        HumanTask task =
                new HumanTask(
                        new TaskId("t-1"),
                        new WorkflowInstanceId("i-1"),
                        "review",
                        HumanTaskKind.BEFORE_ADDED,
                        HumanTaskStatus.PENDING,
                        List.of(),
                        Map.of("k", "v"),
                        new TaskId("t-0"),
                        new TaskId("t-2"),
                        "a-1",
                        NOW,
                        NOW,
                        NOW,
                        NOW,
                        Map.of("m", "1"));

        assertThat(task.formData()).containsEntry("k", "v");
        assertThat(task.metadata()).containsEntry("m", "1");
        assertThatThrownBy(() -> task.formData().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> task.metadata().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("TaskAssignment：COMPLETED 必须带 record，其余状态必须不带")
    void assignmentRecordPresence() {
        assertThat(
                        new TaskAssignment(
                                        user("u-1"),
                                        AssignmentStatus.COMPLETED,
                                        approve("u-1"),
                                        NOW,
                                        NOW,
                                        null)
                                .record())
                .isNotNull();

        assertThatThrownBy(
                        () ->
                                new TaskAssignment(
                                        user("u-1"),
                                        AssignmentStatus.COMPLETED,
                                        null,
                                        NOW,
                                        NOW,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("record");
        assertThatThrownBy(
                        () ->
                                new TaskAssignment(
                                        user("u-1"),
                                        AssignmentStatus.TRANSFERRED,
                                        approve("u-1"),
                                        NOW,
                                        NOW,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("record");
        assertThatThrownBy(
                        () ->
                                new TaskAssignment(
                                        null, AssignmentStatus.PENDING, null, NOW, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TaskAssignment(user("u-1"), null, null, NOW, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("ApproveRecord：extra 归一化为空 map，decision 必填")
    void approveRecord() {
        ApproveRecord record =
                new ApproveRecord("u-1", "u-0", ActionDecision.REJECT, "no", NOW, null);

        assertThat(record.assignee()).isEqualTo("u-1");
        assertThat(record.onBehalfOf()).isEqualTo("u-0");
        assertThat(record.decision()).isEqualTo(ActionDecision.REJECT);
        assertThat(record.extra()).isEmpty();

        assertThatThrownBy(
                        () ->
                                new ApproveRecord(
                                        null, null, ActionDecision.APPROVE, null, NOW, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApproveRecord("u-1", null, null, null, NOW, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("ParticipantSpec：type 与 value 必填，attributes 归一化为空 map")
    void participantSpec() {
        ParticipantSpec spec = new ParticipantSpec(ParticipantType.GROUP, "g-1", null);

        assertThat(spec.type()).isEqualTo(ParticipantType.GROUP);
        assertThat(spec.value()).isEqualTo("g-1");
        assertThat(spec.attributes()).isEmpty();

        assertThatThrownBy(() -> new ParticipantSpec(null, "g-1", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ParticipantSpec(ParticipantType.USER, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
