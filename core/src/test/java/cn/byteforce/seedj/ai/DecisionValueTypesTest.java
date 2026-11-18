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
 * 契约值类型的校验口径：JSON body 载体容忍可空字段，不可逆动作只接受已批准的审批。
 *
 * <p>这两条都是“签不出坏记录/token”的前置条件：账本体按字段组装，必定出现 null value；不可逆动作若允许 未通过的审批写进 token，守卫的错误码表里没有对应拒绝项，等于放行。
 */
class DecisionValueTypesTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DECISION = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RECORD = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ACTOR = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ON_BEHALF_OF = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID TASK_CONTRACT =
            UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID CONVERSATION = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final Instant ISSUED_AT = Instant.parse("2026-11-18T00:00:00Z");
    private static final String ARGS_HASH = "sha256:" + "a".repeat(64);

    @Test
    @DisplayName("body/args/payload 允许 null value，并保留键的插入顺序")
    void body_may_carry_null_values() {
        Map<String, Object> withNulls = new LinkedHashMap<>();
        withNulls.put("externalRef", null);
        withNulls.put("errorCode", "ticket_locked");
        withNulls.put("commandId", null);

        Map<String, Object> body = record(withNulls).body();
        Map<String, Object> args = intent(withNulls).args();
        Map<String, Object> payload = proposal(withNulls).payload();

        for (Map<String, Object> value : List.of(body, args, payload)) {
            assertThat(value).containsKey("externalRef").containsKey("commandId");
            assertThat(value.get("externalRef")).isNull();
            assertThat(value.get("commandId")).isNull();
            assertThat(value.get("errorCode")).isEqualTo("ticket_locked");
            assertThat(value.keySet()).containsExactly("externalRef", "errorCode", "commandId");
        }
        assertThatThrownBy(() -> body.put("late", "x"))
                .as("null 值不值得可变：取回的映射仍必须只读")
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("不可逆动作的 token claims 只接受已批准的审批快照")
    void irreversible_claims_require_approved_status() {
        List<ApprovalStatus> notApproved =
                List.of(
                        ApprovalStatus.PENDING,
                        ApprovalStatus.REJECTED,
                        ApprovalStatus.EXPIRED,
                        ApprovalStatus.UNKNOWN);

        for (ApprovalStatus status : notApproved) {
            assertThatThrownBy(
                            () ->
                                    claims(
                                            ActionClass.IRREVERSIBLE_FINANCIAL,
                                            approval(status)))
                    .as("status %s 不得进入 token", status)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("approval")
                    .hasMessageContaining(status.wire());
        }

        DecisionTokenClaims approved =
                claims(ActionClass.IRREVERSIBLE_EXTERNAL, approval(ApprovalStatus.APPROVED));
        assertThat(approved.approval().status()).isEqualTo(ApprovalStatus.APPROVED);
    }

    private static DecisionRecord record(Map<String, Object> body) {
        return new DecisionRecord(
                RECORD,
                DECISION,
                DecisionRecordKind.OUTCOME,
                DecisionRecord.partitionOf(TENANT),
                1,
                "0".repeat(64),
                "1".repeat(64),
                ISSUED_AT,
                TENANT,
                body);
    }

    private static DecisionIntent intent(Map<String, Object> args) {
        return new DecisionIntent(
                DECISION,
                TENANT,
                TASK_CONTRACT,
                CONVERSATION,
                0,
                DecisionKind.EXECUTE_TOOL,
                ActionClass.REVERSIBLE,
                command(),
                ARGS_HASH,
                "b".repeat(64),
                args,
                "用户要求创建工单",
                List.of(),
                new ModelRef("seedj-agent", "2026-11", "prompt-v1", "d".repeat(64)),
                PolicyRef.none(),
                new AgentIdentity(ACTOR, ON_BEHALF_OF, null, "seedj-ai-starter"),
                null);
    }

    private static Proposal proposal(Map<String, Object> payload) {
        return new Proposal(
                DecisionKind.EXECUTE_TOOL,
                ActionClass.REVERSIBLE,
                command(),
                payload,
                "用户要求创建工单",
                List.of());
    }

    private static CommandRef command() {
        return new CommandRef("ticket.create", List.of(TENANT), 7L);
    }

    private static ApprovalSnapshot approval(ApprovalStatus status) {
        boolean approved = status == ApprovalStatus.APPROVED;
        return new ApprovalSnapshot(
                "fixture-instance",
                "fixture-task",
                status,
                ISSUED_AT,
                approved ? ACTOR : null,
                approved ? ISSUED_AT : null,
                ARGS_HASH,
                3L);
    }

    private static DecisionTokenClaims claims(
            ActionClass actionClass, ApprovalSnapshot approval) {
        return new DecisionTokenClaims(
                DecisionTokenClaims.VERSION,
                DECISION,
                RECORD,
                command(),
                ARGS_HASH,
                ACTOR,
                ON_BEHALF_OF,
                TENANT.toString(),
                actionClass,
                PolicyRef.none(),
                approval,
                ISSUED_AT,
                ISSUED_AT.plusSeconds(60),
                "seedj-server");
    }
}
