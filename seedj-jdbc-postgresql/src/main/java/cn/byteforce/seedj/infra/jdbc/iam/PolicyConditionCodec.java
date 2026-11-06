package cn.byteforce.seedj.infra.jdbc.iam;

import cn.byteforce.seedj.iam.policy.PolicyComparisonOperator;
import cn.byteforce.seedj.iam.policy.PolicyCondition;
import cn.byteforce.seedj.iam.policy.PolicyOperand;
import cn.byteforce.seedj.iam.policy.PolicyOperandSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts the typed policy AST to a tagged JSON object and back. The tag is
 * explicit so a JSONB column never has to guess a Java subtype.
 */
public final class PolicyConditionCodec {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public String encode(PolicyCondition condition) {
        return JSON.writeValueAsString(encodeNode(condition));
    }

    public PolicyCondition decode(String json) {
        if (json == null || json.isBlank()) {
            return PolicyCondition.Always.INSTANCE;
        }
        return decodeNode(JSON.readTree(json));
    }

    private ObjectNode encodeNode(PolicyCondition condition) {
        ObjectNode node = JSON.createObjectNode();
        if (condition == PolicyCondition.Always.INSTANCE) {
            node.put("type", "always");
        } else if (condition instanceof PolicyCondition.And and) {
            node.put("type", "and");
            node.set("conditions", encodeAll(and.conditions()));
        } else if (condition instanceof PolicyCondition.Or or) {
            node.put("type", "or");
            node.set("conditions", encodeAll(or.conditions()));
        } else if (condition instanceof PolicyCondition.Not not) {
            node.put("type", "not");
            node.set("condition", encodeNode(not.condition()));
        } else if (condition instanceof PolicyCondition.Compare compare) {
            node.put("type", "compare");
            node.set("left", encodeOperand(compare.left()));
            node.put("operator", compare.operator().name());
            node.set("right", encodeOperand(compare.right()));
        } else if (condition instanceof PolicyCondition.Exists exists) {
            node.put("type", "exists");
            node.set("operand", encodeOperand(exists.operand()));
        } else {
            throw new IllegalArgumentException("unsupported policy condition: " + condition);
        }
        return node;
    }

    private ObjectNode encodeOperand(PolicyOperand operand) {
        ObjectNode node = JSON.createObjectNode();
        node.put("source", operand.source().name());
        node.put("path", operand.path());
        if (operand.source() == PolicyOperandSource.LITERAL) {
            node.set("value", JSON.valueToTree(operand.literal()));
        }
        return node;
    }

    private ArrayNode encodeAll(List<PolicyCondition> conditions) {
        ArrayNode array = JSON.createArrayNode();
        conditions.forEach(condition -> array.add(encodeNode(condition)));
        return array;
    }

    private PolicyCondition decodeNode(JsonNode node) {
        String type = node.get("type").asString();
        return switch (type) {
            case "always" -> PolicyCondition.Always.INSTANCE;
            case "and" -> new PolicyCondition.And(decodeAll(node.get("conditions")));
            case "or" -> new PolicyCondition.Or(decodeAll(node.get("conditions")));
            case "not" -> new PolicyCondition.Not(decodeNode(node.get("condition")));
            case "compare" -> new PolicyCondition.Compare(
                    decodeOperand(node.get("left")),
                    PolicyComparisonOperator.valueOf(node.get("operator").asString()),
                    decodeOperand(node.get("right")));
            case "exists" -> new PolicyCondition.Exists(decodeOperand(node.get("operand")));
            default -> throw new IllegalArgumentException("unsupported policy condition type: " + type);
        };
    }

    private List<PolicyCondition> decodeAll(JsonNode node) {
        List<PolicyCondition> conditions = new ArrayList<>();
        for (JsonNode child : node) {
            conditions.add(decodeNode(child));
        }
        return conditions;
    }

    private PolicyOperand decodeOperand(JsonNode node) {
        PolicyOperandSource source = PolicyOperandSource.valueOf(node.get("source").asString());
        String path = node.get("path") == null ? "" : node.get("path").asString();
        if (source == PolicyOperandSource.LITERAL) {
            Object value = node.get("value") == null ? null
                    : JSON.treeToValue(node.get("value"), Object.class);
            return new PolicyOperand(source, path, value);
        }
        return new PolicyOperand(source, path, null);
    }
}
