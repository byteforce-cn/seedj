package cn.byteforce.seedj.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 账本记录体的唯一实现：键名、嵌套形状、null 省略与键序。
 *
 * <p>断言整棵 map：记录体参与 {@code record_hash}，任何键改名、嵌套改形或漏省略 null 都会换掉两套账本的哈希。
 */
class DecisionRecordBodiesTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TASK_CONTRACT =
            UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID CONVERSATION =
            UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID ACTOR = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ON_BEHALF_OF =
            UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID PROXY_BINDING =
            UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID TARGET = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID COMMAND = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant AT = Instant.parse("2026-11-18T00:00:00Z");
    private static final String ARGS_HASH = "sha256:" + "a".repeat(64);

    @Test
    @DisplayName("intent：完整记录体（含 action_class/args/policy/approval）整棵 map 逐键对齐")
    void intent_body_is_complete() {
        DecisionIntent intent =
                new DecisionIntent(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        TENANT,
                        TASK_CONTRACT,
                        CONVERSATION,
                        3,
                        DecisionKind.EXECUTE_TOOL,
                        ActionClass.IRREVERSIBLE_FINANCIAL,
                        new CommandRef("payment.refund", List.of(TARGET), 7L),
                        ARGS_HASH,
                        "b".repeat(64),
                        Map.of("title", "退款", "attempt", 1),
                        "用户要求退款",
                        List.of(new Evidence("message", "im://conversation/6666", "c".repeat(64))),
                        new ModelRef("seedj-agent", "2026-11", "prompt-v1", "d".repeat(64)),
                        new PolicyRef(
                                "opa",
                                "ai/v1",
                                9L,
                                12L,
                                "allow",
                                List.of("r-1", "r-2"),
                                "opa://e/1"),
                        new AgentIdentity(ACTOR, ON_BEHALF_OF, PROXY_BINDING, "seedj-ai-starter"),
                        approved());

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("schema", "agent.decision.v1");
        expected.put("record_kind", "intent");
        expected.put("task_contract_id", TASK_CONTRACT.toString());
        expected.put("conversation_id", CONVERSATION.toString());
        expected.put("step", 3);
        expected.put("kind", "execute_tool");
        expected.put("action_class", "irreversible_financial");
        expected.put(
                "command",
                Map.of(
                        "type",
                        "payment.refund",
                        "targets",
                        List.of(TARGET.toString()),
                        "expectedVersion",
                        7L));
        expected.put("args_hash", ARGS_HASH);
        expected.put("idem_fingerprint", "b".repeat(64));
        expected.put("args", Map.of("title", "退款", "attempt", 1));
        expected.put("rationale", "用户要求退款");
        expected.put(
                "evidence",
                List.of(
                        Map.of(
                                "kind",
                                "message",
                                "ref",
                                "im://conversation/6666",
                                "digest",
                                "c".repeat(64))));
        expected.put(
                "model",
                Map.of(
                        "name",
                        "seedj-agent",
                        "version",
                        "2026-11",
                        "prompt_ver",
                        "prompt-v1",
                        "params_digest",
                        "d".repeat(64)));
        expected.put(
                "policy",
                Map.of(
                        "engine",
                        "opa",
                        "bundle",
                        "ai/v1",
                        "bundle_version",
                        9L,
                        "epoch",
                        12L,
                        "verdict",
                        "allow",
                        "rule_ids",
                        List.of("r-1", "r-2"),
                        "explain_ref",
                        "opa://e/1"));
        expected.put(
                "identity",
                Map.of(
                        "actor",
                        ACTOR.toString(),
                        "on_behalf_of",
                        ON_BEHALF_OF.toString(),
                        "proxy_binding_id",
                        PROXY_BINDING.toString(),
                        "acting_service",
                        "seedj-ai-starter"));
        expected.put("approval", approvalFields());

        assertThat(DecisionRecordBodies.intent(intent)).isEqualTo(expected);
        assertThat(DecisionRecordBodies.intent(intent).keySet())
                .containsExactly(
                        "schema",
                        "record_kind",
                        "task_contract_id",
                        "conversation_id",
                        "step",
                        "kind",
                        "action_class",
                        "command",
                        "args_hash",
                        "idem_fingerprint",
                        "args",
                        "rationale",
                        "evidence",
                        "model",
                        "policy",
                        "identity",
                        "approval");
    }

    @Test
    @DisplayName("intent：可选字段为空时省略 action_class/args/policy/approval 与 null 子字段")
    void intent_body_omits_absent_fields() {
        DecisionIntent intent =
                new DecisionIntent(
                        UUID.fromString("00000000-0000-0000-0000-000000000002"),
                        TENANT,
                        TASK_CONTRACT,
                        CONVERSATION,
                        2,
                        DecisionKind.ASK_USER,
                        null,
                        new CommandRef("ticket.query", List.of(), null),
                        ARGS_HASH,
                        "b".repeat(64),
                        null,
                        "需要用户补充信息",
                        List.of(new Evidence("message", null, null)),
                        new ModelRef("seedj-agent", "2026-11", null, null),
                        null,
                        new AgentIdentity(ACTOR, null, null, null),
                        null);

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("schema", "agent.decision.v1");
        expected.put("record_kind", "intent");
        expected.put("task_contract_id", TASK_CONTRACT.toString());
        expected.put("conversation_id", CONVERSATION.toString());
        expected.put("step", 2);
        expected.put("kind", "ask_user");
        expected.put("command", Map.of("type", "ticket.query", "targets", List.of()));
        expected.put("args_hash", ARGS_HASH);
        expected.put("idem_fingerprint", "b".repeat(64));
        expected.put("rationale", "需要用户补充信息");
        expected.put("evidence", List.of(Map.of("kind", "message")));
        expected.put("model", Map.of("name", "seedj-agent", "version", "2026-11"));
        expected.put("identity", Map.of("actor", ACTOR.toString()));

        assertThat(DecisionRecordBodies.intent(intent)).isEqualTo(expected);
    }

    @Test
    @DisplayName("approval：schema + record_kind + 审批快照")
    void approval_body() {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("schema", "agent.decision.v1");
        expected.put("record_kind", "approval");
        expected.put("approval", approvalFields());

        assertThat(DecisionRecordBodies.approval(approved())).isEqualTo(expected);
    }

    @Test
    @DisplayName("outcome：source + 完整 effect")
    void outcome_body() {
        DecisionEffect effect =
                new DecisionEffect(
                        EffectStatus.FAILED, "ext-1", "audit-1", COMMAND, "E_DENIED", AT);

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("schema", "agent.decision.v1");
        expected.put("record_kind", "outcome");
        expected.put("source", "runtime_reported");
        expected.put(
                "effect",
                Map.of(
                        "status",
                        "failed",
                        "external_ref",
                        "ext-1",
                        "audit_entry_id",
                        "audit-1",
                        "command_id",
                        COMMAND.toString(),
                        "error_code",
                        "E_DENIED",
                        "completed_at",
                        AT.toString()));

        assertThat(DecisionRecordBodies.outcome(effect, OutcomeSource.RUNTIME_REPORTED))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("outcome：effect 的空字段省略")
    void outcome_body_omits_absent_effect_fields() {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("schema", "agent.decision.v1");
        expected.put("record_kind", "outcome");
        expected.put("source", "reconciled");
        expected.put("effect", Map.of("status", "unknown"));

        assertThat(DecisionRecordBodies.outcome(DecisionEffect.unknown(), OutcomeSource.RECONCILED))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("reconciled：outcome 的记录体 + reason + supersedes_record_id")
    void reconciled_body() {
        String supersedes = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("schema", "agent.decision.v1");
        expected.put("record_kind", "reconciled");
        expected.put("source", "reconciled");
        expected.put("effect", Map.of("status", "ok", "audit_entry_id", "audit-9"));
        expected.put("reason", "与外部系统对齐");
        expected.put("supersedes_record_id", supersedes);

        assertThat(
                        DecisionRecordBodies.reconciled(
                                DecisionEffect.ok("audit-9", null), "与外部系统对齐", supersedes))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("reconciled：没有被修正的 outcome 时省略 supersedes_record_id")
    void reconciled_body_without_supersedes() {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("schema", "agent.decision.v1");
        expected.put("record_kind", "reconciled");
        expected.put("source", "reconciled");
        expected.put("effect", Map.of("status", "unknown"));
        expected.put("reason", "执行中崩溃，待核对");

        assertThat(DecisionRecordBodies.reconciled(DecisionEffect.unknown(), "执行中崩溃，待核对", null))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("入参为 null 抛 NullPointerException")
    void rejectsNullArguments() {
        assertThatThrownBy(() -> DecisionRecordBodies.intent(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("intent");
        assertThatThrownBy(() -> DecisionRecordBodies.approval(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("snapshot");
        assertThatThrownBy(() -> DecisionRecordBodies.outcome(null, OutcomeSource.RECONCILED))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("effect");
        assertThatThrownBy(() -> DecisionRecordBodies.outcome(DecisionEffect.unknown(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("source");
        assertThatThrownBy(
                        () -> DecisionRecordBodies.reconciled(DecisionEffect.unknown(), null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("reason");
    }

    private static ApprovalSnapshot approved() {
        return new ApprovalSnapshot(
                "wf-instance", "wf-task", ApprovalStatus.APPROVED, AT, ACTOR, AT, ARGS_HASH, 1L);
    }

    private static Map<String, Object> approvalFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("instance_id", "wf-instance");
        fields.put("task_id", "wf-task");
        fields.put("status", "approved");
        fields.put("requested_at", AT.toString());
        fields.put("approver", ACTOR.toString());
        fields.put("approved_at", AT.toString());
        fields.put("args_hash", ARGS_HASH);
        fields.put("instance_revision", 1L);
        return fields;
    }
}
