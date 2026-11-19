package cn.byteforce.seedj.starter.ai.approval;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.workflow.spi.AnyApprove;
import cn.byteforce.seedj.workflow.spi.StepKind;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinition;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 审批工作流定义的形状：一个 HUMAN_TASK + 一个终态 ACTIVITY。 */
class ApprovalWorkflowDefinitionTest {

    private static final String APPROVER = "018f2e3a-0000-7000-8000-000000000118";

    @Test
    @DisplayName("定义含审批与 close 两步，审批为任意一人通过")
    void definitionHasOneHumanTaskAndCloseStep() {
        ApprovalProperties props =
                new ApprovalProperties(
                        true, "ns", "appr", "1.0", List.of(APPROVER), null, false, null, null);

        WorkflowDefinition definition =
                ApprovalWorkflowDefinition.definition(props, props.approverIds());

        assertThat(definition.id().namespace()).isEqualTo("ns");
        assertThat(definition.id().name()).isEqualTo("appr");
        assertThat(definition.steps()).hasSize(2);
        assertThat(definition.steps().get(0).stepName()).isEqualTo("approval");
        assertThat(definition.steps().get(0).kind()).isEqualTo(StepKind.HUMAN_TASK);
        assertThat(definition.steps().get(0).humanTask().signCompletion())
                .isInstanceOf(AnyApprove.class);
        assertThat(definition.steps().get(0).humanTask().rejectTargets()).containsExactly("close");
        assertThat(definition.steps().get(0).humanTask().assignees())
                .singleElement()
                .satisfies(
                        assignee ->
                                assertThat(assignee.value())
                                        .isEqualTo(UUID.fromString(APPROVER).toString()));
        assertThat(definition.steps().get(1).stepName()).isEqualTo("close");
        assertThat(definition.steps().get(1).kind()).isEqualTo(StepKind.ACTIVITY);
    }
}
