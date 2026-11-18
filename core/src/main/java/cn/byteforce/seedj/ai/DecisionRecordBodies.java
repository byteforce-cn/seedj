package cn.byteforce.seedj.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 账本记录体（{@code DecisionRecord.body}）的唯一实现：内存账本与 PostgreSQL 账本共用。
 *
 * <p>{@code record_hash} 覆盖整条记录（见 {@link LedgerHash}），所以两套实现必须写出逐字节相同的记录体： 这里集中定义键名、嵌套结构与
 * null 省略规则，任何一方都不再自己拼 map。
 *
 * <p><b>格式</b>（{@code schema = "agent.decision.v1"}；null 字段一律省略；键序确定）：
 *
 * <ul>
 *   <li>共同前缀：{@code schema}、{@code record_kind}（小写线格式名）；
 *   <li>{@code intent}：{@code task_contract_id}、{@code conversation_id}、{@code step}、{@code kind}、 {@code
 *       action_class}（仅 {@code kind == execute_tool}）、{@code command}（{@code
 *       {type,targets[],expectedVersion}}；{@code targets} 为 UUID 字符串列表）、{@code args_hash}、 {@code
 *       idem_fingerprint}、{@code args}（空则省略）、{@code rationale}、{@code evidence[]}（{@code
 *       {kind,ref,digest}}）、{@code model}（{@code {name,version,prompt_ver,params_digest}}）、 {@code policy}（{@code
 *       {engine,bundle,bundle_version,epoch,verdict,rule_ids[],explain_ref}}）、{@code identity}（{@code
 *       {actor,on_behalf_of,proxy_binding_id,acting_service}}）、{@code approval}（仅需要审批的动作分类）；
 *   <li>{@code approval}：{@code approval}（{@code
 *       {instance_id,task_id,status,requested_at,approver,approved_at,args_hash,instance_revision}}）；
 *   <li>{@code outcome}：{@code source}（{@code runtime_reported} / {@code reconciled}）、{@code effect}（{@code
 *       {status,external_ref,audit_entry_id,command_id,error_code,completed_at}}）；
 *   <li>{@code reconciled}：同 {@code outcome}，另加 {@code reason} 与（已有 outcome 时）{@code
 *       supersedes_record_id}。
 * </ul>
 *
 * <p>时间字段序列化为 {@link java.time.Instant#toString()}，标识序列化为 UUID 字符串。返回的 map 只含 {@code
 * String}、{@code Number}、{@code Boolean}、{@code null}、{@code Map} 与 {@code List}， 可直接交给 JSON 序列化。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class DecisionRecordBodies {

    /** 记录体与 {@code schema_version} 列共用的 schema 名。 */
    public static final String SCHEMA = "agent.decision.v1";

    private DecisionRecordBodies() {}

    /**
     * 组装 {@code intent} 记录体。
     *
     * @param intent 决策意图
     * @return 记录体（不可变视图，嵌套结构为普通 map/list）
     * @throws NullPointerException {@code intent} 为 {@code null}
     */
    public static Map<String, Object> intent(DecisionIntent intent) {
        Objects.requireNonNull(intent, "intent");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", SCHEMA);
        body.put("record_kind", DecisionRecordKind.INTENT.wire());
        body.put("task_contract_id", intent.taskContractId().toString());
        body.put("conversation_id", intent.conversationId().toString());
        body.put("step", intent.step());
        body.put("kind", intent.kind().wire());
        if (intent.actionClass() != null) {
            body.put("action_class", intent.actionClass().wire());
        }
        body.put("command", command(intent.command()));
        body.put("args_hash", intent.argsHash());
        body.put("idem_fingerprint", intent.idemFingerprint());
        if (!intent.args().isEmpty()) {
            body.put("args", new LinkedHashMap<>(intent.args()));
        }
        body.put("rationale", intent.rationale());
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (Evidence item : intent.evidence()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            putIfPresent(entry, "kind", item.kind());
            putIfPresent(entry, "ref", item.ref());
            putIfPresent(entry, "digest", item.digest());
            evidence.add(entry);
        }
        body.put("evidence", evidence);
        body.put("model", model(intent.model()));
        if (intent.policy() != null) {
            body.put("policy", policy(intent.policy()));
        }
        body.put("identity", identity(intent.identity()));
        if (intent.approval() != null) {
            body.put("approval", approvalFields(intent.approval()));
        }
        return Collections.unmodifiableMap(body);
    }

    /**
     * 组装 {@code approval} 记录体。
     *
     * @param snapshot 审批快照
     * @return 记录体（不可变视图，嵌套结构为普通 map）
     * @throws NullPointerException {@code snapshot} 为 {@code null}
     */
    public static Map<String, Object> approval(ApprovalSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", SCHEMA);
        body.put("record_kind", DecisionRecordKind.APPROVAL.wire());
        body.put("approval", approvalFields(snapshot));
        return Collections.unmodifiableMap(body);
    }

    /**
     * 组装 {@code outcome} 记录体。
     *
     * @param effect 执行结局
     * @param source 结局来源
     * @return 记录体（不可变视图，嵌套结构为普通 map）
     * @throws NullPointerException {@code effect} 或 {@code source} 为 {@code null}
     */
    public static Map<String, Object> outcome(DecisionEffect effect, OutcomeSource source) {
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(source, "source");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", SCHEMA);
        body.put("record_kind", DecisionRecordKind.OUTCOME.wire());
        body.put("source", source.wire());
        body.put("effect", effectFields(effect));
        return Collections.unmodifiableMap(body);
    }

    /**
     * 组装 {@code reconciled} 记录体。
     *
     * @param effect 核对后的结局
     * @param reason 核对依据说明
     * @param supersedesRecordId 被修正的 outcome 记录标识；{@code null} 表示该决策没有 outcome，省略该键
     * @return 记录体（不可变视图，嵌套结构为普通 map）
     * @throws NullPointerException {@code effect} 或 {@code reason} 为 {@code null}
     */
    public static Map<String, Object> reconciled(
            DecisionEffect effect, String reason, String supersedesRecordId) {
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(reason, "reason");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", SCHEMA);
        body.put("record_kind", DecisionRecordKind.RECONCILED.wire());
        body.put("source", OutcomeSource.RECONCILED.wire());
        body.put("effect", effectFields(effect));
        body.put("reason", reason);
        if (supersedesRecordId != null) {
            body.put("supersedes_record_id", supersedesRecordId);
        }
        return Collections.unmodifiableMap(body);
    }

    private static Map<String, Object> effectFields(DecisionEffect effect) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", effect.status().wire());
        putIfPresent(fields, "external_ref", effect.externalRef());
        putIfPresent(fields, "audit_entry_id", effect.auditEntryId());
        putIfPresent(fields, "command_id", stringOf(effect.commandId()));
        putIfPresent(fields, "error_code", effect.errorCode());
        putIfPresent(fields, "completed_at", stringOf(effect.completedAt()));
        return fields;
    }

    private static Map<String, Object> command(CommandRef command) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", command.type());
        fields.put("targets", command.targets().stream().map(UUID::toString).toList());
        putIfPresent(fields, "expectedVersion", command.expectedVersion());
        return fields;
    }

    private static Map<String, Object> model(ModelRef model) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", model.name());
        fields.put("version", model.version());
        putIfPresent(fields, "prompt_ver", model.promptVer());
        putIfPresent(fields, "params_digest", model.paramsDigest());
        return fields;
    }

    private static Map<String, Object> policy(PolicyRef policy) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("engine", policy.engine());
        putIfPresent(fields, "bundle", policy.bundle());
        putIfPresent(fields, "bundle_version", policy.bundleVersion());
        putIfPresent(fields, "epoch", policy.epoch());
        putIfPresent(fields, "verdict", policy.verdict());
        fields.put("rule_ids", List.copyOf(policy.ruleIds()));
        putIfPresent(fields, "explain_ref", policy.explainRef());
        return fields;
    }

    private static Map<String, Object> identity(AgentIdentity identity) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("actor", identity.actor().toString());
        putIfPresent(fields, "on_behalf_of", stringOf(identity.onBehalfOf()));
        putIfPresent(fields, "proxy_binding_id", stringOf(identity.proxyBindingId()));
        putIfPresent(fields, "acting_service", identity.actingService());
        return fields;
    }

    private static Map<String, Object> approvalFields(ApprovalSnapshot snapshot) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("instance_id", snapshot.instanceId());
        fields.put("task_id", snapshot.taskId());
        fields.put("status", snapshot.status().wire());
        putIfPresent(fields, "requested_at", stringOf(snapshot.requestedAt()));
        putIfPresent(fields, "approver", stringOf(snapshot.approver()));
        putIfPresent(fields, "approved_at", stringOf(snapshot.approvedAt()));
        fields.put("args_hash", snapshot.argsHash());
        putIfPresent(fields, "instance_revision", snapshot.instanceRevision());
        return fields;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String stringOf(Object value) {
        return value == null ? null : value.toString();
    }
}
