package cn.byteforce.seedj.infra.local.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.workflow.spi.WorkflowCapabilities;
import cn.byteforce.seedj.workflow.spi.WorkflowProvider;
import cn.byteforce.seedj.workflow.spi.WorkflowProviderContractTest;
import org.junit.jupiter.api.Test;

/** 内存实现跑通 v2.4 契约 TCK，并声明全部 21 项能力。 */
class InMemoryWorkflowProviderContractTest extends WorkflowProviderContractTest {

    @Override
    protected WorkflowProvider createProvider() {
        return new InMemoryWorkflowProvider();
    }

    @Test
    void declaresFullApprovalCapabilities() {
        WorkflowCapabilities capabilities = createProvider().capabilities();

        assertThat(capabilities.supportsCompensation()).isTrue();
        assertThat(capabilities.supportsSearch()).isTrue();
        assertThat(capabilities.supportsHistory()).isTrue();
        assertThat(capabilities.supportsVersioning()).isTrue();
        assertThat(capabilities.supportsSignalIdempotency()).isTrue();
        assertThat(capabilities.supportsHumanTasks()).isTrue();
        assertThat(capabilities.supportsRejectToStep()).isTrue();
        assertThat(capabilities.supportsAddAssignee()).isTrue();
        assertThat(capabilities.supportsAddAssigneeBefore()).isTrue();
        assertThat(capabilities.supportsAddAssigneeAfter()).isTrue();
        assertThat(capabilities.supportsRemoveAssignee()).isTrue();
        assertThat(capabilities.supportsTransfer()).isTrue();
        assertThat(capabilities.supportsDelegate()).isTrue();
        assertThat(capabilities.supportsWithdraw()).isTrue();
        assertThat(capabilities.supportsWithdrawInstance()).isTrue();
        assertThat(capabilities.supportsUrge()).isTrue();
        assertThat(capabilities.supportsCc()).isTrue();
        assertThat(capabilities.supportsDynamicForm()).isTrue();
        assertThat(capabilities.supportsParticipantResolution()).isTrue();
        assertThat(capabilities.supportsProviderClock()).isTrue();
        assertThat(capabilities.supportsResumeWorkflow()).isTrue();
    }
}
