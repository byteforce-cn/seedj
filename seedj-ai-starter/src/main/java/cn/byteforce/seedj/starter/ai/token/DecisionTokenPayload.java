package cn.byteforce.seedj.starter.ai.token;

import cn.byteforce.seedj.ai.ActionClass;
import cn.byteforce.seedj.ai.ApprovalSnapshot;
import cn.byteforce.seedj.ai.ApprovalStatus;
import cn.byteforce.seedj.ai.CommandRef;
import cn.byteforce.seedj.ai.DecisionTokenClaims;
import cn.byteforce.seedj.ai.PolicyRef;
import cn.byteforce.seedj.core.utils.CanonicalJsonHelper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * token payload 的编解码：spec §7.1 的键名、null 省略规则与类型转换的唯一实现。
 *
 * <p>编码产出审计规范化文本（键字典序、无空白、UTF-8 不转义），直接作为 JWS payload 的字节；{@code
 * expectedVersion} 键恒在（{@code null} 写成 JSON {@code null}），{@code targets} 是字符串数组且顺序保留，
 * {@code policy}/{@code approval} 为 {@code null} 时整个键省略。
 *
 * <p>解码是严格的：缺键、类型不符、UUID 或时间无法解析都抛 {@link IllegalArgumentException}，由校验方统一映射成
 * {@code token_invalid}——半解析的 claim 不许继续参与比对。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class DecisionTokenPayload {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private DecisionTokenPayload() {}

    /**
     * 编码为规范 JSON 文本。
     *
     * @param claims 待编码 claim
     * @return 键字典序、无空白的 JSON 文本
     */
    static String canonical(DecisionTokenClaims claims) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", claims.v());
        payload.put("decision_id", claims.decisionId().toString());
        payload.put("ledger_intent_id", claims.ledgerIntentId().toString());
        payload.put("command", command(claims.command()));
        payload.put("args_hash", claims.argsHash());
        payload.put("actor", claims.actor().toString());
        payload.put("on_behalf_of", claims.onBehalfOf().toString());
        payload.put("tenant", claims.tenant());
        payload.put("action_class", claims.actionClass().wire());
        if (claims.policy() != null) {
            payload.put("policy", policy(claims.policy()));
        }
        if (claims.approval() != null) {
            payload.put("approval", approval(claims.approval()));
        }
        payload.put("iat", claims.issuedAt().getEpochSecond());
        payload.put("exp", claims.expiresAt().getEpochSecond());
        payload.put("aud", claims.audience());
        return CanonicalJsonHelper.canonicalize(MAPPER.valueToTree(payload));
    }

    /**
     * 从 payload 文本还原 claim。
     *
     * @param payloadJson JWS payload 的 UTF-8 文本
     * @return claim
     * @throws IllegalArgumentException 不是 JSON 对象、缺键、类型不符或值非法
     */
    static DecisionTokenClaims parse(String payloadJson) {
        JsonNode payload = CanonicalJsonHelper.parse(payloadJson);
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("decision token payload is not a JSON object");
        }
        return new DecisionTokenClaims(
                intClaim(payload, "v"),
                uuidClaim(payload, "decision_id"),
                uuidClaim(payload, "ledger_intent_id"),
                command(required(payload, "command")),
                textClaim(payload, "args_hash"),
                uuidClaim(payload, "actor"),
                uuidClaim(payload, "on_behalf_of"),
                textClaim(payload, "tenant"),
                actionClass(textClaim(payload, "action_class")),
                policy(payload.get("policy")),
                approval(payload.get("approval")),
                Instant.ofEpochSecond(longClaim(payload, "iat")),
                Instant.ofEpochSecond(longClaim(payload, "exp")),
                textClaim(payload, "aud"));
    }

    private static Map<String, Object> command(CommandRef command) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", command.type());
        List<String> targets = new ArrayList<>();
        for (UUID target : command.targets()) {
            targets.add(target.toString());
        }
        fields.put("targets", targets);
        fields.put("expectedVersion", command.expectedVersion());
        return fields;
    }

    private static Map<String, Object> policy(PolicyRef policy) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("engine", policy.engine());
        putIfPresent(fields, "bundle", policy.bundle());
        putIfPresent(fields, "bundle_version", policy.bundleVersion());
        putIfPresent(fields, "epoch", policy.epoch());
        putIfPresent(fields, "verdict", policy.verdict());
        fields.put("rule_ids", policy.ruleIds());
        putIfPresent(fields, "explain_ref", policy.explainRef());
        return fields;
    }

    private static Map<String, Object> approval(ApprovalSnapshot approval) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("instance_id", approval.instanceId());
        fields.put("task_id", approval.taskId());
        fields.put("status", approval.status().wire());
        if (approval.requestedAt() != null) {
            fields.put("requested_at", approval.requestedAt().toString());
        }
        if (approval.approver() != null) {
            fields.put("approver", approval.approver().toString());
        }
        if (approval.approvedAt() != null) {
            fields.put("approved_at", approval.approvedAt().toString());
        }
        fields.put("args_hash", approval.argsHash());
        putIfPresent(fields, "instance_revision", approval.instanceRevision());
        return fields;
    }

    private static CommandRef command(JsonNode node) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("claim command must be a JSON object");
        }
        JsonNode targets = required(node, "targets");
        if (!targets.isArray()) {
            throw new IllegalArgumentException("claim command.targets must be an array");
        }
        List<UUID> parsed = new ArrayList<>();
        for (JsonNode target : targets) {
            if (!target.isString()) {
                throw new IllegalArgumentException("claim command.targets must hold strings");
            }
            parsed.add(uuid(target.stringValue(), "command.targets[]"));
        }
        return new CommandRef(textClaim(node, "type"), parsed, optionalLong(node, "expectedVersion"));
    }

    private static PolicyRef policy(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("claim policy must be a JSON object");
        }
        List<String> ruleIds = new ArrayList<>();
        JsonNode rules = node.get("rule_ids");
        if (rules != null && !rules.isNull()) {
            if (!rules.isArray()) {
                throw new IllegalArgumentException("claim policy.rule_ids must be an array");
            }
            for (JsonNode rule : rules) {
                if (!rule.isString()) {
                    throw new IllegalArgumentException("claim policy.rule_ids must hold strings");
                }
                ruleIds.add(rule.stringValue());
            }
        }
        return new PolicyRef(
                textClaim(node, "engine"),
                optionalText(node, "bundle"),
                optionalLong(node, "bundle_version"),
                optionalLong(node, "epoch"),
                optionalText(node, "verdict"),
                ruleIds,
                optionalText(node, "explain_ref"));
    }

    private static ApprovalSnapshot approval(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("claim approval must be a JSON object");
        }
        JsonNode approver = node.get("approver");
        return new ApprovalSnapshot(
                textClaim(node, "instance_id"),
                textClaim(node, "task_id"),
                ApprovalStatus.fromWire(textClaim(node, "status")),
                optionalInstant(node, "requested_at"),
                approver == null || approver.isNull()
                        ? null
                        : uuid(approver.stringValue(), "approval.approver"),
                optionalInstant(node, "approved_at"),
                textClaim(node, "args_hash"),
                optionalLong(node, "instance_revision"));
    }

    private static ActionClass actionClass(String wire) {
        for (ActionClass actionClass : ActionClass.values()) {
            if (actionClass.wire().equals(wire)) {
                return actionClass;
            }
        }
        throw new IllegalArgumentException("unknown action_class: " + wire);
    }

    private static JsonNode required(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("missing claim: " + name);
        }
        return value;
    }

    private static String textClaim(JsonNode node, String name) {
        JsonNode value = required(node, name);
        if (!value.isString()) {
            throw new IllegalArgumentException("claim " + name + " must be a string");
        }
        return value.stringValue();
    }

    private static UUID uuidClaim(JsonNode node, String name) {
        return uuid(textClaim(node, name), name);
    }

    private static UUID uuid(String value, String name) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("claim " + name + " is not a uuid", e);
        }
    }

    private static long longClaim(JsonNode node, String name) {
        JsonNode value = required(node, name);
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException("claim " + name + " must be an integer");
        }
        return value.longValue();
    }

    private static int intClaim(JsonNode node, String name) {
        long value = longClaim(node, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("claim " + name + " is out of int range");
        }
        return (int) value;
    }

    private static Long optionalLong(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException("claim " + name + " must be an integer");
        }
        return value.longValue();
    }

    private static String optionalText(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new IllegalArgumentException("claim " + name + " must be a string");
        }
        return value.stringValue();
    }

    private static Instant optionalInstant(JsonNode node, String name) {
        String value = optionalText(node, name);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("claim " + name + " is not an instant", e);
        }
    }

    private static void putIfPresent(Map<String, Object> fields, String name, Object value) {
        if (value != null) {
            fields.put(name, value);
        }
    }
}
