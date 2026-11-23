package cn.byteforce.seedj.infra.coord.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.workflow.spi.HumanTaskNotFoundException;
import cn.byteforce.seedj.workflow.spi.TaskId;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionId;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionVersion;
import cn.byteforce.seedj.workflow.spi.WorkflowInstanceId;
import cn.byteforce.seedj.workflow.spi.WorkflowProviderException;
import org.junit.jupiter.api.Test;

class CoordWorkflowIdsTest {

    private final CoordWorkflowIds ids = new CoordWorkflowIds("seedj");

    @Test
    void instanceIdMustBeResolvableFromRoundId() {
        ids.requireValidInstanceId(new WorkflowInstanceId("agent-approval-1234"));

        assertThatThrownBy(
                        () -> ids.requireValidInstanceId(new WorkflowInstanceId("a:b")))
                .isInstanceOf(WorkflowProviderException.class)
                .hasMessageContaining("[A-Za-z0-9._-]{1,128}");
        assertThatThrownBy(
                        () -> ids.requireValidInstanceId(new WorkflowInstanceId("a/b")))
                .isInstanceOf(WorkflowProviderException.class);
        assertThatThrownBy(
                        () -> ids.requireValidInstanceId(new WorkflowInstanceId("a".repeat(129))))
                .isInstanceOf(WorkflowProviderException.class);
    }

    @Test
    void roundIdDecodesToInstanceAndSeatIdsAreRejected() {
        assertThat(ids.instanceIdOf(new TaskId("wf-1:round-0001"))).isEqualTo("wf-1");
        assertThatThrownBy(() -> ids.instanceIdOf(new TaskId("wf-1:task-0001")))
                .isInstanceOf(HumanTaskNotFoundException.class);
        assertThatThrownBy(() -> ids.instanceIdOf(new TaskId("wf-1:round-")))
                .isInstanceOf(HumanTaskNotFoundException.class);
        assertThatThrownBy(() -> ids.instanceIdOf(new TaskId("round-0001")))
                .isInstanceOf(HumanTaskNotFoundException.class);
    }

    @Test
    void namespaceAndVersionRoundTrip() {
        assertThat(ids.coordNamespace("contract"))
                .isEqualTo("seedj:workflow:contract");
        assertThat(ids.seedjNamespace("seedj:workflow:contract")).isEqualTo("contract");
        assertThat(ids.seedjNamespace("other:workflow:contract")).isNull();

        WorkflowDefinitionId id = new WorkflowDefinitionId("contract", "approval");
        assertThat(ids.coordName(id)).isEqualTo("approval");
        assertThat(ids.coordVersion(new WorkflowDefinitionVersion(2, 3))).isEqualTo("2.3");
        assertThat(ids.seedjVersion("2.3")).isEqualTo(new WorkflowDefinitionVersion(2, 3));
        assertThat(ids.seedjVersion("unparsed")).isEqualTo(new WorkflowDefinitionVersion(0, 0));
    }

    @Test
    void definitionPartsRejectSlashAndControlCharacters() {
        assertThatThrownBy(() -> ids.coordNamespace("a/b"))
                .isInstanceOf(WorkflowProviderException.class);
        assertThatThrownBy(
                        () -> ids.coordName(new WorkflowDefinitionId("contract", "a b")))
                .isInstanceOf(WorkflowProviderException.class);
    }
}
