package cn.byteforce.seedj.infra.coord.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;
import cn.byteforce.coord.sdk.workflow.WorkflowClient;
import cn.byteforce.seedj.workflow.spi.AllApprove;
import cn.byteforce.seedj.workflow.spi.HumanTaskSpec;
import cn.byteforce.seedj.workflow.spi.ParticipantSpec;
import cn.byteforce.seedj.workflow.spi.ParticipantType;
import cn.byteforce.seedj.workflow.spi.RetryPolicy;
import cn.byteforce.seedj.workflow.spi.StepDefinition;
import cn.byteforce.seedj.workflow.spi.StepKind;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinition;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionId;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionNotFoundException;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionVersion;
import cn.byteforce.seedj.workflow.spi.WorkflowProviderException;
import cn.byteforce.seedj.workflow.spi.WorkflowVersionConflictException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T6：定义注册/查找的 coord 往返（mock WorkflowClient，无集群）。 */
class CoordWorkflowDefinitionsTest {

    private static final String NAMESPACE = "test:workflow:contract";

    private final WorkflowClient workflows = mock(WorkflowClient.class);
    private final CoordWorkflowIds ids = new CoordWorkflowIds("test");
    private final CoordWorkflowDefinitions definitions =
            new CoordWorkflowDefinitions(
                    workflows, new WorkflowDefinitionCompiler(ids, "sign-admin", 64 * 1024), ids);

    private final WorkflowDefinition definition = approval("approval", 1, 0);

    @BeforeEach
    void deployable() {
        when(workflows.deployDefinition(anyString(), anyString()))
                .thenAnswer(
                        invocation ->
                                new cn.byteforce.coord.sdk.workflow.WorkflowDefinition(
                                        "coord-def-1",
                                        "approval",
                                        invocation.getArgument(1),
                                        "1.0",
                                        "ACTIVE",
                                        0));
    }

    @Test
    void registerDeploysTheCompiledDefinitionIntoTheSeedjNamespace() {
        definitions.register(definition);

        String expectedYaml =
                new WorkflowDefinitionCompiler(ids, "sign-admin", 64 * 1024).compile(definition);
        verify(workflows).deployDefinition(NAMESPACE, expectedYaml);
    }

    @Test
    void registeringAnExistingVersionIsAConflict() {
        when(workflows.deployDefinition(anyString(), anyString()))
                .thenThrow(new CoordException(ErrorCode.ALREADY_EXISTS, "exists"));

        assertThatThrownBy(() -> definitions.register(definition))
                .isInstanceOf(WorkflowVersionConflictException.class)
                .hasMessageContaining("contract/approval@1.0");
    }

    @Test
    void findWithExplicitVersionReadsThePinnedDeployment() {
        when(workflows.getDefinition(NAMESPACE, "approval", "1.0"))
                .thenReturn(deployed(definition));

        Optional<WorkflowDefinition> found =
                definitions.find(definition.id(), new WorkflowDefinitionVersion(1, 0));

        assertThat(found).contains(definition);
        verify(workflows).getDefinition(NAMESPACE, "approval", "1.0");
    }

    @Test
    void findWithoutVersionPicksTheHighestSeedjVersion() {
        when(workflows.listDefinitionVersions(NAMESPACE, "approval"))
                .thenReturn(List.of(summary("1.0"), summary("1.2"), summary("0.9")));
        WorkflowDefinition latest = approval("approval", 1, 2);
        when(workflows.getDefinition(NAMESPACE, "approval", "1.2")).thenReturn(deployed(latest));

        Optional<WorkflowDefinition> found = definitions.find(definition.id(), null);

        assertThat(found).contains(latest);
        verify(workflows).listDefinitionVersions(NAMESPACE, "approval");
        verify(workflows).getDefinition(NAMESPACE, "approval", "1.2");
        verifyNoMoreInteractions(workflows);
    }

    @Test
    void findReturnsEmptyWhenNoVersionIsDeployed() {
        when(workflows.listDefinitionVersions(NAMESPACE, "approval"))
                .thenThrow(new CoordException(ErrorCode.NOT_FOUND, "none"));

        assertThat(definitions.find(definition.id(), null)).isEmpty();
    }

    @Test
    void findReturnsEmptyWhenTheDeploymentDisappeared() {
        when(workflows.getDefinition(NAMESPACE, "approval", "1.0"))
                .thenThrow(new CoordException(ErrorCode.NOT_FOUND, "none"));

        assertThat(definitions.find(definition.id(), new WorkflowDefinitionVersion(1, 0)))
                .isEmpty();
    }

    @Test
    void findFailsWhenTheDeploymentHasNoSeedjTags() {
        when(workflows.getDefinition(NAMESPACE, "approval", "1.0"))
                .thenReturn(
                        new cn.byteforce.coord.sdk.workflow.WorkflowDefinition(
                                "coord-def-1",
                                "approval",
                                "{\"document\":{\"name\":\"approval\"}}",
                                "1.0",
                                "ACTIVE",
                                0));

        assertThatThrownBy(
                        () ->
                                definitions.find(
                                        definition.id(), new WorkflowDefinitionVersion(1, 0)))
                .isInstanceOf(WorkflowProviderException.class)
                .hasMessageContaining("seedj.definition");
    }

    @Test
    void findFailsWhenTheEmbeddedDefinitionIdDoesNotMatch() {
        when(workflows.getDefinition(eq(NAMESPACE), eq("approval"), anyString()))
                .thenReturn(deployed(approval("something-else", 1, 0)));

        assertThatThrownBy(
                        () ->
                                definitions.find(
                                        definition.id(), new WorkflowDefinitionVersion(1, 0)))
                .isInstanceOf(WorkflowProviderException.class)
                .hasMessageContaining("id mismatch");
    }

    @Test
    void deploymentIdResolvesTheCoordWorkflowId() {
        when(workflows.getDefinition(NAMESPACE, "approval", "1.0"))
                .thenReturn(deployed(definition));

        assertThat(definitions.deploymentId(definition.id(), new WorkflowDefinitionVersion(1, 0)))
                .isEqualTo("coord-def-1");
    }

    @Test
    void deploymentIdFailsClosedWhenTheDefinitionIsMissing() {
        when(workflows.getDefinition(any(), any(), any()))
                .thenThrow(new CoordException(ErrorCode.NOT_FOUND, "none"));

        assertThatThrownBy(
                        () ->
                                definitions.deploymentId(
                                        definition.id(), new WorkflowDefinitionVersion(1, 0)))
                .isInstanceOf(WorkflowDefinitionNotFoundException.class);
    }

    @Test
    void definitionIdOfRejectsForeignNamespaces() {
        assertThatThrownBy(() -> definitions.definitionIdOf("other:namespace", "approval"))
                .isInstanceOf(WorkflowProviderException.class)
                .hasMessageContaining("non-seedj");
    }

    private static cn.byteforce.coord.sdk.workflow.WorkflowDefinitionVersion summary(
            String version) {
        return new cn.byteforce.coord.sdk.workflow.WorkflowDefinitionVersion(
                version, "coord-" + version, "ACTIVE", 0);
    }

    private static cn.byteforce.coord.sdk.workflow.WorkflowDefinition deployed(
            WorkflowDefinition definition) {
        String yaml =
                MAPPER.writeValueAsString(
                        Map.of(
                                "document",
                                Map.of(
                                        "tags",
                                        Map.of(
                                                "seedj.definition",
                                                WorkflowDefinitionCodec.toJson(definition))),
                                "do",
                                List.of()));
        return new cn.byteforce.coord.sdk.workflow.WorkflowDefinition(
                "coord-def-1",
                definition.id().name(),
                yaml,
                definition.version().major() + "." + definition.version().minor(),
                "ACTIVE",
                0);
    }

    private static WorkflowDefinition approval(String name, int major, int minor) {
        HumanTaskSpec spec =
                new HumanTaskSpec(
                        List.of(new ParticipantSpec(ParticipantType.USER, "u1", Map.of())),
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
        StepDefinition review =
                new StepDefinition(
                        "review",
                        StepKind.HUMAN_TASK,
                        Duration.ofMinutes(5),
                        RetryPolicy.noRetry(),
                        List.of(),
                        Map.of(),
                        spec,
                        null);
        return new WorkflowDefinition(
                new WorkflowDefinitionId("contract", name),
                new WorkflowDefinitionVersion(major, minor),
                List.of(review),
                Duration.ofMinutes(5),
                null,
                Map.of());
    }

    private static final tools.jackson.databind.json.JsonMapper MAPPER =
            tools.jackson.databind.json.JsonMapper.builder().build();
}
