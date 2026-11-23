package cn.byteforce.seedj.infra.coord.workflow;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;
import cn.byteforce.coord.sdk.workflow.WorkflowClient;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinition;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionId;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionVersion;
import cn.byteforce.seedj.workflow.spi.WorkflowProviderException;
import cn.byteforce.seedj.workflow.spi.WorkflowVersionConflictException;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 定义注册与查找：seedj 定义随 coord Deploy 落 {@code document.tags}。 */
final class CoordWorkflowDefinitions {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final WorkflowClient workflows;
    private final WorkflowDefinitionCompiler compiler;
    private final CoordWorkflowIds ids;

    CoordWorkflowDefinitions(
            WorkflowClient workflows, WorkflowDefinitionCompiler compiler, CoordWorkflowIds ids) {
        this.workflows = workflows;
        this.compiler = compiler;
        this.ids = ids;
    }

    void register(WorkflowDefinition definition) {
        String yaml = compiler.compile(definition);
        try {
            workflows.deployDefinition(ids.coordNamespace(definition.id().namespace()), yaml);
        } catch (CoordException e) {
            if (e.getErrorCode() == ErrorCode.ALREADY_EXISTS) {
                throw new WorkflowVersionConflictException(
                        "workflow definition "
                                + definition.id().namespace()
                                + "/"
                                + definition.id().name()
                                + "@"
                                + definition.version().major()
                                + "."
                                + definition.version().minor()
                                + " already exists with different content");
            }
            throw e;
        }
    }

    Optional<WorkflowDefinition> find(
            WorkflowDefinitionId id, WorkflowDefinitionVersion version) {
        String namespace = ids.coordNamespace(id.namespace());
        String name = ids.coordName(id);
        String coordVersion = version == null ? latestVersion(namespace, name) : ids.coordVersion(version);
        if (coordVersion == null) {
            return Optional.empty();
        }
        try {
            var deployed = workflows.getDefinition(namespace, name, coordVersion);
            return Optional.of(decode(deployed.definitionYaml(), id, version));
        } catch (CoordException e) {
            if (e.getErrorCode() == ErrorCode.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** 返回 coord 定义 id（{@code startByDefinition*} 的入参）；不存在时抛未找到。 */
    String deploymentId(WorkflowDefinitionId id, WorkflowDefinitionVersion version) {
        String namespace = ids.coordNamespace(id.namespace());
        String name = ids.coordName(id);
        String coordVersion =
                version == null ? latestVersion(namespace, name) : ids.coordVersion(version);
        if (coordVersion == null) {
            throw new cn.byteforce.seedj.workflow.spi.WorkflowDefinitionNotFoundException(
                    "workflow definition not found: "
                            + id.namespace()
                            + "/"
                            + id.name()
                            + "@latest");
        }
        try {
            return workflows.getDefinition(namespace, name, coordVersion).workflowId();
        } catch (CoordException e) {
            if (e.getErrorCode() == ErrorCode.NOT_FOUND) {
                throw new cn.byteforce.seedj.workflow.spi.WorkflowDefinitionNotFoundException(
                        "workflow definition not found: "
                                + id.namespace()
                                + "/"
                                + id.name()
                                + "@"
                                + coordVersion);
            }
            throw e;
        }
    }

    /** 实例启动时把 coord 定义身份还原成 seedj 定义身份（GetStatus 的钉死版本）。 */
    WorkflowDefinitionId definitionIdOf(String coordNamespace, String name) {
        String seedjNamespace = ids.seedjNamespace(coordNamespace);
        if (seedjNamespace == null) {
            throw new WorkflowProviderException(
                    "workflow instance belongs to a non-seedj coord namespace: " + coordNamespace);
        }
        return new WorkflowDefinitionId(seedjNamespace, name);
    }

    String latestVersion(String namespace, String name) {
        try {
            var versions = workflows.listDefinitionVersions(namespace, name);
            WorkflowDefinitionVersion latest = null;
            for (var version : versions) {
                WorkflowDefinitionVersion parsed = ids.seedjVersion(version.version());
                if (latest == null
                        || parsed.major() > latest.major()
                        || (parsed.major() == latest.major() && parsed.minor() > latest.minor())) {
                    latest = parsed;
                }
            }
            return latest == null ? null : ids.coordVersion(latest);
        } catch (CoordException e) {
            if (e.getErrorCode() == ErrorCode.NOT_FOUND) {
                return null;
            }
            throw e;
        }
    }

    private WorkflowDefinition decode(
            String definitionYaml,
            WorkflowDefinitionId requestedId,
            WorkflowDefinitionVersion requestedVersion) {
        try {
            JsonNode root = MAPPER.readTree(definitionYaml);
            JsonNode tags = root.path("document").path("tags");
            JsonNode embedded = tags.get("seedj.definition");
            if (embedded == null || embedded.isNull()) {
                throw new WorkflowProviderException(
                        "coord definition is not registered by seedj (missing"
                                + " document.tags[seedj.definition]): "
                                + requestedId.namespace()
                                + "/"
                                + requestedId.name());
            }
            WorkflowDefinition decoded = WorkflowDefinitionCodec.fromJson(embedded.stringValue());
            if (!decoded.id().equals(requestedId)) {
                throw new WorkflowProviderException(
                        "embedded seedj definition id mismatch: expected "
                                + requestedId
                                + ", got "
                                + decoded.id());
            }
            if (requestedVersion != null && !decoded.version().equals(requestedVersion)) {
                throw new WorkflowProviderException(
                        "embedded seedj definition version mismatch: expected "
                                + requestedVersion
                                + ", got "
                                + decoded.version());
            }
            return decoded;
        } catch (WorkflowProviderException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new WorkflowProviderException(
                    "cannot decode coord definition " + requestedId + ": " + e, e);
        }
    }
}
