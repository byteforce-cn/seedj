package cn.byteforce.seedj.infra.local.workflow;

import cn.byteforce.seedj.workflow.spi.WorkflowDefinition;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionId;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionNotFoundException;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionVersion;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/** 拆分后的内存工作流协作者：InMemoryWorkflowDefinitionStore。 */
final class InMemoryWorkflowDefinitionStore {

    private final InMemoryWorkflowSupport support;

    InMemoryWorkflowDefinitionStore(InMemoryWorkflowSupport support) {
        this.support = support;
    }

    public void register(WorkflowDefinition definition) {
        Objects.requireNonNull(definition, "definition cannot be null");
        support.definitions.put(definitionKey(definition.id(), definition.version()), definition);
    }

    public Optional<WorkflowDefinition> findDefinition(
            WorkflowDefinitionId id, WorkflowDefinitionVersion version) {
        Objects.requireNonNull(id, "id cannot be null");
        if (version != null) {
            return Optional.ofNullable(support.definitions.get(definitionKey(id, version)));
        }
        return support.definitions.values().stream()
                .filter(d -> d.id().equals(id))
                .max(
                        Comparator.comparing(
                                WorkflowDefinition::version,
                                Comparator.comparingInt(WorkflowDefinitionVersion::major)
                                        .thenComparingInt(WorkflowDefinitionVersion::minor)));
    }

    WorkflowDefinition resolveDefinition(
            WorkflowDefinitionId id, WorkflowDefinitionVersion version) {
        if (version != null) {
            WorkflowDefinition definition = support.definitions.get(definitionKey(id, version));
            if (definition == null) {
                throw new WorkflowDefinitionNotFoundException(
                        "definition " + id + " version " + versionText(version) + " not found");
            }
            return definition;
        }
        return findDefinition(id, null)
                .orElseThrow(
                        () ->
                                new WorkflowDefinitionNotFoundException(
                                        "definition " + id + " not found"));
    }

    static String definitionKey(WorkflowDefinitionId id, WorkflowDefinitionVersion version) {
        return id.namespace()
                + '\u0000'
                + id.name()
                + '\u0000'
                + version.major()
                + '.'
                + version.minor();
    }

    static String versionText(WorkflowDefinitionVersion version) {
        return version.major() + "." + version.minor();
    }
}
