package cn.byteforce.seedj.infra.coord.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.byteforce.coord.sdk.kv.KvClient;
import cn.byteforce.coord.sdk.workflow.WorkflowClient;
import cn.byteforce.seedj.infra.coord.workflow.CoordWorkflowSidecar.ServerClock;
import cn.byteforce.seedj.workflow.spi.ParticipantResolutionContext;
import cn.byteforce.seedj.workflow.spi.ParticipantSpec;
import cn.byteforce.seedj.workflow.spi.ParticipantType;
import cn.byteforce.seedj.workflow.spi.StartWorkflowRequest;
import cn.byteforce.seedj.workflow.spi.WorkflowCapabilities;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionId;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionVersion;
import cn.byteforce.seedj.workflow.spi.WorkflowInstanceId;
import cn.byteforce.seedj.workflow.spi.WorkflowProviderException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** T13：门面契约——能力位、实例 id 前置校验、close() 零交互、provider 时钟。 */
class CoordWorkflowProviderTest {

    private final WorkflowClient workflows = mock(WorkflowClient.class);
    private final KvClient kv = mock(KvClient.class);

    @Test
    void everyCapabilityIsAdvertised() {
        CoordWorkflowProvider provider = provider();

        WorkflowCapabilities capabilities = provider.capabilities();

        assertThat(
                        List.of(
                                capabilities.supportsCompensation(),
                                capabilities.supportsSearch(),
                                capabilities.supportsHistory(),
                                capabilities.supportsVersioning(),
                                capabilities.supportsSignalIdempotency(),
                                capabilities.supportsHumanTasks(),
                                capabilities.supportsRejectToStep(),
                                capabilities.supportsAddAssignee(),
                                capabilities.supportsAddAssigneeBefore(),
                                capabilities.supportsAddAssigneeAfter(),
                                capabilities.supportsRemoveAssignee(),
                                capabilities.supportsTransfer(),
                                capabilities.supportsDelegate(),
                                capabilities.supportsWithdraw(),
                                capabilities.supportsWithdrawInstance(),
                                capabilities.supportsUrge(),
                                capabilities.supportsCc(),
                                capabilities.supportsDynamicForm(),
                                capabilities.supportsParticipantResolution(),
                                capabilities.supportsProviderClock(),
                                capabilities.supportsResumeWorkflow()))
                .hasSize(21)
                .containsOnly(true);
    }

    @Test
    void startRejectsAnAmbiguousInstanceIdBeforeCallingCoord() {
        CoordWorkflowProvider provider = provider();

        assertThatThrownBy(
                        () ->
                                provider.start(
                                        new StartWorkflowRequest(
                                                new WorkflowInstanceId("bad:id"),
                                                new WorkflowDefinitionId("contract", "approval"),
                                                new WorkflowDefinitionVersion(1, 0),
                                                "starter",
                                                Map.of())))
                .isInstanceOf(WorkflowProviderException.class)
                .hasMessageContaining("[A-Za-z0-9._-]{1,128}");
        verifyNoInteractions(workflows, kv);
    }

    @Test
    void closeLeavesTheOwnedClientsAlone() {
        provider().close();

        verifyNoInteractions(workflows, kv);
    }

    @Test
    void nowNeverGoesBackwards() {
        ServerClock clock = new ServerClock();
        clock.observe(Instant.parse("2026-11-22T10:00:00Z"));
        clock.observe(Instant.parse("2026-11-22T09:00:00Z"));

        assertThat(clock.now()).isEqualTo(Instant.parse("2026-11-22T10:00:00Z"));

        clock.observe(Instant.parse("2026-11-22T11:00:00Z"));
        assertThat(clock.now()).isEqualTo(Instant.parse("2026-11-22T11:00:00Z"));
    }

    @Test
    void nowFallsBackToTheLocalClockBeforeAnyResponse() {
        assertThat(provider().now()).isNotNull();
    }

    @Test
    void resolveParticipantsExpandsCommaSeparatedUsers() {
        CoordWorkflowProvider provider = provider();

        List<ParticipantSpec> resolved =
                provider.resolveParticipants(
                        new ParticipantResolutionContext(
                                "${approvers}",
                                new WorkflowInstanceId("wf-1"),
                                new WorkflowDefinitionId("contract", "approval"),
                                new WorkflowDefinitionVersion(1, 0),
                                "review",
                                Map.of("approvers", "u7, u8"),
                                Instant.now()));

        assertThat(resolved).extracting(ParticipantSpec::value).containsExactly("u7", "u8");
        assertThat(resolved).extracting(ParticipantSpec::type).containsOnly(ParticipantType.USER);
    }

    @Test
    void missingSignAdminFailsAtConstruction() {
        CoordWorkflowProperties properties = new CoordWorkflowProperties();

        assertThatThrownBy(() -> new CoordWorkflowProvider(workflows, kv, properties, "test"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void describeReturnsEmptyForUnknownInstancesAndDoesNotThrow() {
        when(workflows.getStatus(anyString()))
                .thenThrow(
                        new cn.byteforce.coord.sdk.CoordException(
                                cn.byteforce.coord.sdk.ErrorCode.NOT_FOUND, "missing"));

        assertThat(provider().describe(new WorkflowInstanceId("wf-missing"))).isEmpty();
        verifyNoInteractions(kv);
    }

    private CoordWorkflowProvider provider() {
        CoordWorkflowProperties properties = new CoordWorkflowProperties();
        properties.setSignAdmin("sign-admin");
        return new CoordWorkflowProvider(workflows, kv, properties, "test");
    }
}
