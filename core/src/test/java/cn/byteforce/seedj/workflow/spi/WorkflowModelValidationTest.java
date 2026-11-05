package cn.byteforce.seedj.workflow.spi;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** v2.4 构造期不变量：不允许静默修正或矛盾输入。 */
class WorkflowModelValidationTest {

    private static ParticipantSpec user(String value) {
        return new ParticipantSpec(ParticipantType.USER, value, Map.of());
    }

    private static HumanTaskSpec spec(boolean allowAddAssignee,
                                      Set<AddAssigneePosition> positions) {
        return new HumanTaskSpec(List.of(user("u1")), new AllApprove(), null, Set.of(),
                null, Map.of(), allowAddAssignee, positions, false, false, false, false,
                false, false, null, Map.of());
    }

    @Test
    void addAssigneeRequiresExplicitPositions() {
        assertThatThrownBy(() -> spec(true, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowedAddPositions");

        assertThatThrownBy(() -> spec(true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowedAddPositions");

        assertThat(spec(true, Set.of(AddAssigneePosition.CURRENT)).allowedAddPositions())
                .containsExactly(AddAssigneePosition.CURRENT);
    }

    @Test
    void addAssigneeDisabledRejectsPositions() {
        assertThatThrownBy(() -> spec(false, Set.of(AddAssigneePosition.BEFORE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflicts");
    }

    @Test
    void signCompletionCountCannotExceedAssignees() {
        assertThatThrownBy(() -> new HumanTaskSpec(List.of(user("u1")),
                new AtLeastCount(2), null, Set.of(), null, Map.of(), false, Set.of(),
                false, false, false, false, false, false, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AtLeastCount");
    }

    @Test
    void dueInCannotExceedStepTimeout() {
        HumanTaskSpec taskSpec = new HumanTaskSpec(List.of(user("u1")), new AllApprove(),
                null, Set.of(), null, Map.of(), false, Set.of(), false, false, false,
                false, false, false, Duration.ofSeconds(10), Map.of());

        assertThatThrownBy(() -> new StepDefinition("review", StepKind.HUMAN_TASK,
                Duration.ofSeconds(5), RetryPolicy.noRetry(), List.of(), Map.of(),
                taskSpec, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dueIn");
    }

    @Test
    void stepKindAndSpecMustAgree() {
        assertThatThrownBy(() -> new StepDefinition("review", StepKind.HUMAN_TASK,
                Duration.ofSeconds(5), RetryPolicy.noRetry(), List.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("humanTask");

        assertThatThrownBy(() -> new StepDefinition("task", StepKind.ACTIVITY,
                Duration.ofSeconds(5), RetryPolicy.noRetry(), List.of(), Map.of(),
                spec(false, Set.of()), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-HUMAN_TASK");

        assertThatThrownBy(() -> new StepDefinition("wait", StepKind.WAIT_SIGNAL,
                Duration.ofSeconds(5), RetryPolicy.noRetry(), List.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("waitSignalName");
    }

    @Test
    void assignmentRecordPresenceIsStrict() {
        assertThatThrownBy(() -> new TaskAssignment(user("u1"), AssignmentStatus.COMPLETED,
                null, Instant.now(), Instant.now(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("COMPLETED");

        ApproveRecord record = new ApproveRecord("u1", null, ActionDecision.APPROVE,
                "ok", Instant.now(), Map.of());
        assertThatThrownBy(() -> new TaskAssignment(user("u1"), AssignmentStatus.TRANSFERRED,
                record, Instant.now(), Instant.now(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-COMPLETED");
    }

    @Test
    void actionAndQueryArgumentsAreValidated() {
        assertThatThrownBy(() -> new DelegateAction("a", new TaskId("t"), "u1",
                user("u2"), Duration.ZERO, null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delegateFor");

        assertThatThrownBy(() -> new TaskQuery(null, "u1", "u2", List.of(), null,
                null, null, 10, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mutually exclusive");

        assertThatThrownBy(() -> new ResumeWorkflowAction("a", "  ", null, null,
                Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operator");

        assertThatThrownBy(() -> new CcAction("a", "", List.of(user("u2")), null,
                null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operator");
    }

    @Test
    void percentageAndVersionBounds() {
        assertThatThrownBy(() -> new AtLeastPercentage(0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AtLeastPercentage(1.01))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowDefinitionVersion(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new AtLeastPercentage(1.0).percentage()).isEqualTo(1.0);
    }
}
