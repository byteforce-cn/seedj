package cn.byteforce.seedj.starter.ai.ledger;

import cn.byteforce.seedj.ai.ActionClass;
import cn.byteforce.seedj.ai.CommandRef;
import cn.byteforce.seedj.ai.DecisionRecord;
import cn.byteforce.seedj.ai.PolicyRef;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 从已落账的 {@code intent} 记录体里读回构建 token 与派生 outcome 需要的事实。
 *
 * <p>上报、对账与审批签发都不重新解释请求：主体取 intent 里写下的 {@code identity.actor}，指纹取 {@code
 * args_hash} / {@code idem_fingerprint}，命令与策略取记录体里的同一份值。这样即使配置后来变了，「这条意图对应哪条命令」 的答案仍然不变。
 *
 * @param decisionId 决策标识
 * @param tenantId 租户
 * @param actor 意图里的发起主体（命令记录的 {@code issuer}）
 * @param onBehalfOf 被代理用户
 * @param argsHash 参数指纹
 * @param idemFingerprint 幂等指纹
 * @param command 命令引用
 * @param actionClass 动作分类；非执行工具步骤为 {@code null}
 * @param policy 策略引用；缺失时为 {@link PolicyRef#none()}
 * @author ByteForce
 * @since 1.0.0
 */
public record RecordedIntent(
        UUID recordId,
        UUID decisionId,
        UUID tenantId,
        UUID actor,
        UUID onBehalfOf,
        String argsHash,
        String idemFingerprint,
        String rationale,
        CommandRef command,
        ActionClass actionClass,
        PolicyRef policy) {

    /**
     * 读回一条 {@code intent} 记录。
     *
     * @param record 账本记录
     * @return 意图里的事实
     * @throws NullPointerException {@code record} 为 {@code null}
     * @throws IllegalStateException 记录体缺必填字段
     */
    public static RecordedIntent of(DecisionRecord record) {
        Objects.requireNonNull(record, "record");
        Map<String, Object> body = record.body();
        Map<?, ?> identity = map(body.get("identity"), "identity", record);
        UUID actor = uuid(identity.get("actor"), "identity.actor", record);
        UUID onBehalfOf = uuid(identity.get("on_behalf_of"), "identity.on_behalf_of", record);
        String argsHash = text(body.get("args_hash"), "args_hash", record);
        String idemFingerprint = text(body.get("idem_fingerprint"), "idem_fingerprint", record);
        String rationale = text(body.get("rationale"), "rationale", record);
        Map<?, ?> command = map(body.get("command"), "command", record);
        CommandRef commandRef =
                new CommandRef(
                        text(command.get("type"), "command.type", record),
                        targets(command.get("targets")),
                        version(command.get("expectedVersion")));
        Object actionWire = body.get("action_class");
        ActionClass actionClass =
                actionWire == null ? null : actionClass(actionWire.toString(), record);
        PolicyRef policy = policy(body.get("policy"));
        return new RecordedIntent(
                record.recordId(),
                record.decisionId(),
                record.tenantId(),
                actor,
                onBehalfOf,
                argsHash,
                idemFingerprint,
                rationale,
                commandRef,
                actionClass,
                policy);
    }

    private static PolicyRef policy(Object value) {
        if (!(value instanceof Map<?, ?> fields)) {
            return PolicyRef.none();
        }
        Object ruleIds = fields.get("rule_ids");
        List<String> rules =
                ruleIds instanceof List<?> list
                        ? list.stream().map(String::valueOf).toList()
                        : List.of();
        return new PolicyRef(
                String.valueOf(fields.get("engine")),
                textOrNull(fields.get("bundle")),
                number(fields.get("bundle_version")),
                number(fields.get("epoch")),
                textOrNull(fields.get("verdict")),
                rules,
                textOrNull(fields.get("explain_ref")));
    }

    private static ActionClass actionClass(String wire, DecisionRecord record) {
        try {
            return ActionClass.valueOf(wire.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(
                    "intent 记录含未知 action_class: " + wire + " (" + record.recordId() + ")",
                    error);
        }
    }

    private static Map<?, ?> map(Object value, String field, DecisionRecord record) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException(
                    "intent 记录缺少 " + field + ": " + record.recordId());
        }
        return map;
    }

    private static String text(Object value, String field, DecisionRecord record) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException(
                    "intent 记录缺少 " + field + ": " + record.recordId());
        }
        return text;
    }

    private static String textOrNull(Object value) {
        return value == null ? null : value.toString();
    }

    private static UUID uuid(Object value, String field, DecisionRecord record) {
        try {
            return value == null ? null : UUID.fromString(value.toString());
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(
                    "intent 记录含非法 " + field + ": " + value, error);
        }
    }

    private static List<UUID> targets(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(item -> UUID.fromString(item.toString())).toList();
    }

    private static Long version(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
    }

    private static Long number(Object value) {
        return version(value);
    }
}
