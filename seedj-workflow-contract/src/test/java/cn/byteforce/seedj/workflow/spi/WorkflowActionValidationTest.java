package cn.byteforce.seedj.workflow.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 动作模型：必填字段、默认位置与空集合约束。
 *
 * <p>动作是 Provider 的唯一写入入口，构造期不接受矛盾输入。
 */
class WorkflowActionValidationTest {

    private static final TaskId TASK = new TaskId("t-1");
    private static final Instant NOW = Instant.parse("2026-02-01T08:00:00Z");

    private static ParticipantSpec user(String value) {
        return new ParticipantSpec(ParticipantType.USER, value, Map.of());
    }

    @Test
    @DisplayName("ApproveAction：formData 归一化，三个必填字段")
    void approveAction() {
        ApproveAction action = new ApproveAction("a-1", TASK, "u-1", "ok", null, NOW);

        assertThat(action.actionId()).isEqualTo("a-1");
        assertThat(action.taskId()).isEqualTo(TASK);
        assertThat(action.assignee()).isEqualTo("u-1");
        assertThat(action.comment()).isEqualTo("ok");
        assertThat(action.formData()).isEmpty();
        assertThat(action.occurredAt()).isEqualTo(NOW);

        assertThatThrownBy(() -> new ApproveAction(null, TASK, "u-1", null, null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApproveAction("a-1", null, "u-1", null, null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApproveAction("a-1", TASK, null, null, null, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("RejectAction：targetStep 必填")
    void rejectAction() {
        RejectAction action = new RejectAction("a-1", TASK, "u-1", "draft", "redo", NOW);

        assertThat(action.actionId()).isEqualTo("a-1");
        assertThat(action.taskId()).isEqualTo(TASK);
        assertThat(action.assignee()).isEqualTo("u-1");
        assertThat(action.targetStep()).isEqualTo("draft");
        assertThat(action.comment()).isEqualTo("redo");
        assertThat(action.occurredAt()).isEqualTo(NOW);

        assertThatThrownBy(() -> new RejectAction(null, TASK, "u-1", "draft", null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RejectAction("a-1", null, "u-1", "draft", null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RejectAction("a-1", TASK, null, "draft", null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RejectAction("a-1", TASK, "u-1", null, null, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("AddAssigneeAction：新参与者非空，位置默认 CURRENT")
    void addAssigneeAction() {
        AddAssigneeAction action =
                new AddAssigneeAction(
                        "a-1", TASK, "u-1", List.of(user("u-2")), null, "need review", NOW);

        assertThat(action.actionId()).isEqualTo("a-1");
        assertThat(action.taskId()).isEqualTo(TASK);
        assertThat(action.operator()).isEqualTo("u-1");
        assertThat(action.newAssignees()).containsExactly(user("u-2"));
        assertThat(action.position()).isEqualTo(AddAssigneePosition.CURRENT);
        assertThat(action.reason()).isEqualTo("need review");
        assertThat(action.occurredAt()).isEqualTo(NOW);

        assertThat(
                        new AddAssigneeAction(
                                        "a-1",
                                        TASK,
                                        "u-1",
                                        List.of(user("u-2")),
                                        AddAssigneePosition.BEFORE,
                                        null,
                                        NOW)
                                .position())
                .isEqualTo(AddAssigneePosition.BEFORE);

        assertThatThrownBy(() -> new AddAssigneeAction("a-1", TASK, "u-1", null, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newAssignees");
        assertThatThrownBy(
                        () ->
                                new AddAssigneeAction(
                                        null, TASK, "u-1", List.of(user("u-2")), null, null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new AddAssigneeAction(
                                        "a-1", null, "u-1", List.of(user("u-2")), null, null, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("RemoveAssigneeAction：待移除列表非空")
    void removeAssigneeAction() {
        RemoveAssigneeAction action =
                new RemoveAssigneeAction("a-1", TASK, "u-1", List.of("u-2"), "not needed", NOW);

        assertThat(action.actionId()).isEqualTo("a-1");
        assertThat(action.taskId()).isEqualTo(TASK);
        assertThat(action.operator()).isEqualTo("u-1");
        assertThat(action.removedAssigneeIds()).containsExactly("u-2");
        assertThat(action.reason()).isEqualTo("not needed");
        assertThat(action.occurredAt()).isEqualTo(NOW);

        assertThatThrownBy(() -> new RemoveAssigneeAction("a-1", TASK, "u-1", null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("removedAssigneeIds");
        assertThatThrownBy(
                        () ->
                                new RemoveAssigneeAction(
                                        null, TASK, "u-1", List.of("u-2"), null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new RemoveAssigneeAction(
                                        "a-1", null, "u-1", List.of("u-2"), null, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("TransferAction：转办双方必填")
    void transferAction() {
        TransferAction action =
                new TransferAction("a-1", TASK, "u-1", user("u-2"), "handover", NOW);

        assertThat(action.actionId()).isEqualTo("a-1");
        assertThat(action.taskId()).isEqualTo(TASK);
        assertThat(action.fromAssignee()).isEqualTo("u-1");
        assertThat(action.toAssignee()).isEqualTo(user("u-2"));
        assertThat(action.reason()).isEqualTo("handover");
        assertThat(action.occurredAt()).isEqualTo(NOW);

        assertThatThrownBy(() -> new TransferAction(null, TASK, "u-1", user("u-2"), null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TransferAction("a-1", null, "u-1", user("u-2"), null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TransferAction("a-1", TASK, null, user("u-2"), null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TransferAction("a-1", TASK, "u-1", null, null, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("DelegateAction：代理时长必须为正")
    void delegateAction() {
        DelegateAction action =
                new DelegateAction(
                        "a-1", TASK, "u-1", user("u-2"), Duration.ofHours(4), "vacation", NOW);

        assertThat(action.actionId()).isEqualTo("a-1");
        assertThat(action.delegateFor()).isEqualTo(Duration.ofHours(4));
        assertThat(action.fromAssignee()).isEqualTo("u-1");
        assertThat(action.toAssignee()).isEqualTo(user("u-2"));
        assertThat(action.reason()).isEqualTo("vacation");

        assertThatThrownBy(
                        () ->
                                new DelegateAction(
                                        "a-1", TASK, "u-1", user("u-2"), Duration.ZERO, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delegateFor");
        assertThatThrownBy(
                        () ->
                                new DelegateAction(
                                        "a-1",
                                        TASK,
                                        "u-1",
                                        user("u-2"),
                                        Duration.ofSeconds(-1),
                                        null,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new DelegateAction("a-1", TASK, "u-1", user("u-2"), null, null, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("WithdrawAction / WithdrawInstanceAction：目标步骤必填")
    void withdrawActions() {
        WithdrawAction taskLevel = new WithdrawAction("a-1", TASK, "u-1", "draft", "wrong", NOW);

        assertThat(taskLevel.actionId()).isEqualTo("a-1");
        assertThat(taskLevel.taskId()).isEqualTo(TASK);
        assertThat(taskLevel.operator()).isEqualTo("u-1");
        assertThat(taskLevel.targetStep()).isEqualTo("draft");
        assertThat(taskLevel.reason()).isEqualTo("wrong");
        assertThat(taskLevel.occurredAt()).isEqualTo(NOW);

        WithdrawInstanceAction instanceLevel =
                new WithdrawInstanceAction("a-2", "u-1", "draft", "stop", NOW);

        assertThat(instanceLevel.actionId()).isEqualTo("a-2");
        assertThat(instanceLevel.operator()).isEqualTo("u-1");
        assertThat(instanceLevel.targetStep()).isEqualTo("draft");
        assertThat(instanceLevel.reason()).isEqualTo("stop");
        assertThat(instanceLevel.occurredAt()).isEqualTo(NOW);

        assertThatThrownBy(() -> new WithdrawAction("a-1", TASK, "u-1", null, null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WithdrawAction(null, TASK, "u-1", "draft", null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WithdrawInstanceAction("a-2", "u-1", null, null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WithdrawInstanceAction(null, "u-1", "draft", null, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("UrgeAction：操作人必填")
    void urgeAction() {
        UrgeAction action = new UrgeAction("a-1", TASK, "u-1", "friendly reminder", NOW);

        assertThat(action.actionId()).isEqualTo("a-1");
        assertThat(action.taskId()).isEqualTo(TASK);
        assertThat(action.operator()).isEqualTo("u-1");
        assertThat(action.message()).isEqualTo("friendly reminder");
        assertThat(action.occurredAt()).isEqualTo(NOW);

        assertThatThrownBy(() -> new UrgeAction("a-1", TASK, null, null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new UrgeAction("a-1", null, "u-1", null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new UrgeAction(null, TASK, "u-1", null, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("CcAction：操作人非空白、收件人非空")
    void ccAction() {
        CcAction action = new CcAction("a-1", "u-1", List.of(user("u-2")), "review", "fyi", NOW);

        assertThat(action.actionId()).isEqualTo("a-1");
        assertThat(action.operator()).isEqualTo("u-1");
        assertThat(action.recipients()).containsExactly(user("u-2"));
        assertThat(action.stepName()).isEqualTo("review");
        assertThat(action.message()).isEqualTo("fyi");
        assertThat(action.occurredAt()).isEqualTo(NOW);

        assertThatThrownBy(() -> new CcAction("a-1", " ", List.of(user("u-2")), null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operator");
        assertThatThrownBy(() -> new CcAction("a-1", "u-1", null, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recipients");
        assertThatThrownBy(() -> new CcAction(null, "u-1", List.of(user("u-2")), null, null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CcAction("a-1", null, List.of(user("u-2")), null, null, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("ResumeWorkflowAction：newDueIn 可为 null，但不得为 0 或负数")
    void resumeWorkflowAction() {
        ResumeWorkflowAction action = new ResumeWorkflowAction("a-1", "u-1", null, "resume", NOW);

        assertThat(action.actionId()).isEqualTo("a-1");
        assertThat(action.operator()).isEqualTo("u-1");
        assertThat(action.newDueIn()).isNull();
        assertThat(action.reason()).isEqualTo("resume");
        assertThat(action.occurredAt()).isEqualTo(NOW);

        assertThat(
                        new ResumeWorkflowAction("a-1", "u-1", Duration.ofHours(2), null, NOW)
                                .newDueIn())
                .isEqualTo(Duration.ofHours(2));

        assertThatThrownBy(() -> new ResumeWorkflowAction("a-1", "u-1", Duration.ZERO, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newDueIn");
        assertThatThrownBy(() -> new ResumeWorkflowAction("a-1", " ", null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operator");
        assertThatThrownBy(() -> new ResumeWorkflowAction("a-1", null, null, null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ResumeWorkflowAction(null, "u-1", null, null, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("SignalAction：payload 归一化，signalName 必填")
    void signalAction() {
        SignalAction action = new SignalAction("a-1", "payment-received", null, NOW);

        assertThat(action.actionId()).isEqualTo("a-1");
        assertThat(action.signalName()).isEqualTo("payment-received");
        assertThat(action.payload()).isEmpty();
        assertThat(action.occurredAt()).isEqualTo(NOW);

        assertThatThrownBy(() -> new SignalAction("a-1", null, null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SignalAction(null, "s", null, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("ActionRequest：实例与动作必填")
    void actionRequest() {
        ActionRequest request =
                new ActionRequest(
                        new WorkflowInstanceId("i-1"),
                        new UrgeAction("a-1", TASK, "u-1", null, NOW));

        assertThat(request.instanceId()).isEqualTo(new WorkflowInstanceId("i-1"));
        assertThat(request.action().actionId()).isEqualTo("a-1");

        assertThatThrownBy(
                        () ->
                                new ActionRequest(
                                        null, new UrgeAction("a-1", TASK, "u-1", null, NOW)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ActionRequest(new WorkflowInstanceId("i-1"), null))
                .isInstanceOf(NullPointerException.class);
    }
}
