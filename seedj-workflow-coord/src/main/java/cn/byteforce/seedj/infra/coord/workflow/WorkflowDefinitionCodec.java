package cn.byteforce.seedj.infra.coord.workflow;

import cn.byteforce.seedj.workflow.spi.AddAssigneePosition;
import cn.byteforce.seedj.workflow.spi.AllApprove;
import cn.byteforce.seedj.workflow.spi.AnyApprove;
import cn.byteforce.seedj.workflow.spi.AnyRejectTerminates;
import cn.byteforce.seedj.workflow.spi.AtLeastCount;
import cn.byteforce.seedj.workflow.spi.AtLeastPercentage;
import cn.byteforce.seedj.workflow.spi.AtLeastRejects;
import cn.byteforce.seedj.workflow.spi.HumanTaskSpec;
import cn.byteforce.seedj.workflow.spi.ParticipantSpec;
import cn.byteforce.seedj.workflow.spi.ParticipantType;
import cn.byteforce.seedj.workflow.spi.RecordAndContinue;
import cn.byteforce.seedj.workflow.spi.RejectCompletion;
import cn.byteforce.seedj.workflow.spi.RetryPolicy;
import cn.byteforce.seedj.workflow.spi.SignCompletion;
import cn.byteforce.seedj.workflow.spi.StepDefinition;
import cn.byteforce.seedj.workflow.spi.StepKind;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinition;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionId;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionVersion;
import cn.byteforce.seedj.workflow.spi.WorkflowProviderException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * seedj 定义的确定性 JSON 编解码。
 *
 * <p>定义原样内嵌进 coord 定义的 {@code document.tags["seedj.definition"]}；coord 的 {@code definition_hash} 覆盖
 * tags，序列化必须逐字稳定（固定字段顺序、Map 按键排序、无缩进）。 这里不用数据绑定注解：显式字段表让 schema 变更在编译期可见。
 */
final class WorkflowDefinitionCodec {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private WorkflowDefinitionCodec() {}

    static String toJson(WorkflowDefinition definition) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", id(definition.id()));
        root.put("version", version(definition.version()));
        List<Object> steps = new ArrayList<>();
        for (StepDefinition step : definition.steps()) {
            steps.add(step(step));
        }
        root.put("steps", steps);
        root.put("defaultStepTimeoutMs", millis(definition.defaultStepTimeout()));
        root.put("workflowTimeoutMs", millis(definition.workflowTimeout()));
        root.put("metadata", sorted(definition.metadata()));
        return MAPPER.writeValueAsString(root);
    }

    static WorkflowDefinition fromJson(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode id = required(root, "id");
            List<StepDefinition> steps = new ArrayList<>();
            for (JsonNode step : requiredArray(root, "steps")) {
                steps.add(step(step));
            }
            return new WorkflowDefinition(
                    new WorkflowDefinitionId(
                            requiredText(id, "namespace"), requiredText(id, "name")),
                    new WorkflowDefinitionVersion(
                            requiredInt(required(root, "version"), "major"),
                            requiredInt(required(root, "version"), "minor")),
                    steps,
                    duration(optional(root, "defaultStepTimeoutMs")),
                    duration(optional(root, "workflowTimeoutMs")),
                    mapStrings(required(root, "metadata")));
        } catch (WorkflowProviderException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new WorkflowProviderException("invalid embedded seedj definition: " + e, e);
        }
    }

    private static Map<String, Object> id(WorkflowDefinitionId id) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("namespace", id.namespace());
        node.put("name", id.name());
        return node;
    }

    private static Map<String, Object> version(WorkflowDefinitionVersion version) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("major", version.major());
        node.put("minor", version.minor());
        return node;
    }

    private static Map<String, Object> step(StepDefinition step) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("stepName", step.stepName());
        node.put("kind", step.kind().name());
        node.put("timeoutMs", millis(step.timeout()));
        node.put("retryPolicy", retry(step.retryPolicy()));
        node.put("dependsOn", List.copyOf(step.dependsOn()));
        node.put("metadata", sorted(step.metadata()));
        node.put("humanTask", humanTask(step.humanTask()));
        node.put("waitSignalName", step.waitSignalName());
        return node;
    }

    private static Map<String, Object> retry(RetryPolicy retry) {
        if (retry == null) {
            return null;
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("maxAttempts", retry.maxAttempts());
        node.put("initialBackoffMs", retry.initialBackoff().toMillis());
        node.put("maxBackoffMs", retry.maxBackoff().toMillis());
        node.put("multiplier", retry.multiplier());
        return node;
    }

    private static Map<String, Object> humanTask(HumanTaskSpec spec) {
        if (spec == null) {
            return null;
        }
        Map<String, Object> node = new LinkedHashMap<>();
        List<Object> assignees = new ArrayList<>();
        for (ParticipantSpec participant : spec.assignees()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", participant.type().name());
            p.put("value", participant.value());
            p.put("attributes", sorted(participant.attributes()));
            assignees.add(p);
        }
        node.put("assignees", assignees);
        node.put("signCompletion", signCompletion(spec.signCompletion()));
        node.put("rejectCompletion", rejectCompletion(spec.rejectCompletion()));
        node.put("rejectTargets", new ArrayList<>(new java.util.TreeSet<>(spec.rejectTargets())));
        node.put("formKey", spec.formKey());
        node.put("formSchema", sorted(spec.formSchema()));
        node.put("allowAddAssignee", spec.allowAddAssignee());
        List<String> positions = new ArrayList<>();
        spec.allowedAddPositions().stream()
                .sorted()
                .forEach(position -> positions.add(position.name()));
        node.put("allowedAddPositions", positions);
        node.put("allowRemoveAssignee", spec.allowRemoveAssignee());
        node.put("allowTransfer", spec.allowTransfer());
        node.put("allowDelegate", spec.allowDelegate());
        node.put("allowWithdraw", spec.allowWithdraw());
        node.put("allowUrge", spec.allowUrge());
        node.put("allowCc", spec.allowCc());
        node.put("dueInMs", millis(spec.dueIn()));
        node.put("metadata", sorted(spec.metadata()));
        return node;
    }

    private static Map<String, Object> signCompletion(SignCompletion completion) {
        Map<String, Object> node = new LinkedHashMap<>();
        switch (completion) {
            case AllApprove ignored -> node.put("type", "ALL_APPROVE");
            case AnyApprove ignored -> node.put("type", "ANY_APPROVE");
            case AtLeastCount count -> {
                node.put("type", "AT_LEAST_COUNT");
                node.put("count", count.count());
            }
            case AtLeastPercentage percentage -> {
                node.put("type", "AT_LEAST_PERCENTAGE");
                node.put("percentage", percentage.percentage());
            }
        }
        return node;
    }

    private static Map<String, Object> rejectCompletion(RejectCompletion completion) {
        Map<String, Object> node = new LinkedHashMap<>();
        switch (completion) {
            case AnyRejectTerminates ignored -> node.put("type", "ANY_REJECT_TERMINATES");
            case RecordAndContinue ignored -> node.put("type", "RECORD_AND_CONTINUE");
            case AtLeastRejects rejects -> {
                node.put("type", "AT_LEAST_REJECTS");
                node.put("count", rejects.count());
            }
        }
        return node;
    }

    private static StepDefinition step(JsonNode node) {
        return new StepDefinition(
                requiredText(node, "stepName"),
                StepKind.valueOf(requiredText(node, "kind")),
                duration(optional(node, "timeoutMs")),
                retry(optional(node, "retryPolicy")),
                strings(optional(node, "dependsOn")),
                mapStrings(required(node, "metadata")),
                humanTask(optional(node, "humanTask")),
                optionalText(node, "waitSignalName"));
    }

    private static RetryPolicy retry(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return new RetryPolicy(
                requiredInt(node, "maxAttempts"),
                Duration.ofMillis(requiredLong(node, "initialBackoffMs")),
                Duration.ofMillis(requiredLong(node, "maxBackoffMs")),
                requiredDouble(node, "multiplier"));
    }

    private static HumanTaskSpec humanTask(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        List<ParticipantSpec> assignees = new ArrayList<>();
        for (JsonNode participant : requiredArray(node, "assignees")) {
            assignees.add(
                    new ParticipantSpec(
                            ParticipantType.valueOf(requiredText(participant, "type")),
                            requiredText(participant, "value"),
                            mapStrings(required(participant, "attributes"))));
        }
        Set<AddAssigneePosition> positions = new LinkedHashSet<>();
        for (String position : strings(required(node, "allowedAddPositions"))) {
            positions.add(AddAssigneePosition.valueOf(position));
        }
        Set<String> rejectTargets = new LinkedHashSet<>(strings(required(node, "rejectTargets")));
        return new HumanTaskSpec(
                assignees,
                signCompletion(required(node, "signCompletion")),
                rejectCompletion(required(node, "rejectCompletion")),
                rejectTargets,
                optionalText(node, "formKey"),
                mapStrings(required(node, "formSchema")),
                requiredBoolean(node, "allowAddAssignee"),
                positions,
                requiredBoolean(node, "allowRemoveAssignee"),
                requiredBoolean(node, "allowTransfer"),
                requiredBoolean(node, "allowDelegate"),
                requiredBoolean(node, "allowWithdraw"),
                requiredBoolean(node, "allowUrge"),
                requiredBoolean(node, "allowCc"),
                duration(optional(node, "dueInMs")),
                mapStrings(required(node, "metadata")));
    }

    private static SignCompletion signCompletion(JsonNode node) {
        String type = requiredText(node, "type");
        return switch (type) {
            case "ALL_APPROVE" -> new AllApprove();
            case "ANY_APPROVE" -> new AnyApprove();
            case "AT_LEAST_COUNT" -> new AtLeastCount(requiredInt(node, "count"));
            case "AT_LEAST_PERCENTAGE" -> new AtLeastPercentage(requiredDouble(node, "percentage"));
            default -> throw new WorkflowProviderException("unknown signCompletion type: " + type);
        };
    }

    private static RejectCompletion rejectCompletion(JsonNode node) {
        String type = requiredText(node, "type");
        return switch (type) {
            case "ANY_REJECT_TERMINATES" -> new AnyRejectTerminates();
            case "RECORD_AND_CONTINUE" -> new RecordAndContinue();
            case "AT_LEAST_REJECTS" -> new AtLeastRejects(requiredInt(node, "count"));
            default ->
                    throw new WorkflowProviderException("unknown rejectCompletion type: " + type);
        };
    }

    private static Long millis(Duration duration) {
        return duration == null ? null : duration.toMillis();
    }

    private static Duration duration(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return Duration.ofMillis(node.asLong());
    }

    private static Map<String, String> sorted(Map<String, String> map) {
        return map == null ? Map.of() : new TreeMap<>(map);
    }

    private static Map<String, String> mapStrings(JsonNode node) {
        Map<String, String> values = new TreeMap<>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (!node.isObject()) {
            throw new WorkflowProviderException("expected a string map: " + node);
        }
        for (var field : node.properties()) {
            JsonNode value = field.getValue();
            values.put(field.getKey(), value.isString() ? value.stringValue() : value.toString());
        }
        return values;
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new WorkflowProviderException("expected a string array: " + node);
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            values.add(item.stringValue());
        }
        return values;
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = optional(node, field);
        if (value == null || value.isNull()) {
            throw new WorkflowProviderException("missing field: " + field);
        }
        return value;
    }

    private static JsonNode optional(JsonNode node, String field) {
        return node == null ? null : node.get(field);
    }

    private static Iterable<JsonNode> requiredArray(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isArray()) {
            throw new WorkflowProviderException("expected an array: " + field);
        }
        return value;
    }

    private static String requiredText(JsonNode node, String field) {
        return required(node, field).stringValue();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = optional(node, field);
        return value == null || value.isNull() ? null : value.stringValue();
    }

    private static int requiredInt(JsonNode node, String field) {
        return required(node, field).asInt();
    }

    private static long requiredLong(JsonNode node, String field) {
        return required(node, field).asLong();
    }

    private static double requiredDouble(JsonNode node, String field) {
        return required(node, field).asDouble();
    }

    private static boolean requiredBoolean(JsonNode node, String field) {
        return required(node, field).asBoolean();
    }
}
