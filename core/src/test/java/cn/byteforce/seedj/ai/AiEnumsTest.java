package cn.byteforce.seedj.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 线格式枚举的冻结契约：每个常量的 wire 名是 token、账本与告警的匹配依据，冻结后不得改名。
 *
 * <p>往返测试证明“写出去的能读回来”，未知名测试证明“读不懂就拒绝而不是回落到某个默认值”—— 后者是守卫误放行的入口。
 */
class AiEnumsTest {

    @Test
    @DisplayName("DecisionKind 的 wire 名是冻结的小写名")
    void decision_kind_wire_names_are_frozen() {
        assertThat(Arrays.stream(DecisionKind.values()).map(DecisionKind::wire))
                .containsExactly("read", "execute_tool", "ask_user", "escalate", "finish");
    }

    @ParameterizedTest
    @EnumSource(DecisionKind.class)
    @DisplayName("DecisionKind 从 wire 名解析回原常量")
    void decision_kind_round_trips(DecisionKind kind) {
        assertThat(DecisionKind.fromWire(kind.wire())).isSameAs(kind);
    }

    @Test
    @DisplayName("DecisionKind 拒绝未知 wire 名")
    void decision_kind_rejects_unknown_wire_name() {
        assertThatThrownBy(() -> DecisionKind.fromWire("execute"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("execute");
    }

    @Test
    @DisplayName("DecisionRecordKind 的 wire 名是冻结的小写名")
    void decision_record_kind_wire_names_are_frozen() {
        assertThat(Arrays.stream(DecisionRecordKind.values()).map(DecisionRecordKind::wire))
                .containsExactly("intent", "approval", "outcome", "reconciled");
    }

    @ParameterizedTest
    @EnumSource(DecisionRecordKind.class)
    @DisplayName("DecisionRecordKind 从 wire 名解析回原常量")
    void decision_record_kind_round_trips(DecisionRecordKind kind) {
        assertThat(DecisionRecordKind.fromWire(kind.wire())).isSameAs(kind);
    }

    @Test
    @DisplayName("DecisionRecordKind 拒绝未知 wire 名")
    void decision_record_kind_rejects_unknown_wire_name() {
        assertThatThrownBy(() -> DecisionRecordKind.fromWire("INTENT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INTENT");
    }

    @Test
    @DisplayName("EffectStatus 的 wire 名是冻结的小写名")
    void effect_status_wire_names_are_frozen() {
        assertThat(Arrays.stream(EffectStatus.values()).map(EffectStatus::wire))
                .containsExactly("ok", "failed", "unknown", "not_applicable", "not_issued", "rejected");
    }

    @ParameterizedTest
    @EnumSource(EffectStatus.class)
    @DisplayName("EffectStatus 从 wire 名解析回原常量")
    void effect_status_round_trips(EffectStatus status) {
        assertThat(EffectStatus.fromWire(status.wire())).isSameAs(status);
    }

    @Test
    @DisplayName("EffectStatus 拒绝未知 wire 名")
    void effect_status_rejects_unknown_wire_name() {
        assertThatThrownBy(() -> EffectStatus.fromWire("succeeded"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("succeeded");
    }

    @Test
    @DisplayName("OutcomeSource 的 wire 名是冻结的小写名")
    void outcome_source_wire_names_are_frozen() {
        assertThat(Arrays.stream(OutcomeSource.values()).map(OutcomeSource::wire))
                .containsExactly("runtime_reported", "plane_decided", "reconciled");
    }

    @Test
    @DisplayName("ApprovalStatus 的 wire 名是冻结的小写名")
    void approval_status_wire_names_are_frozen() {
        assertThat(Arrays.stream(ApprovalStatus.values()).map(ApprovalStatus::wire))
                .containsExactly("pending", "approved", "rejected", "expired", "unknown");
    }

    @ParameterizedTest
    @EnumSource(ApprovalStatus.class)
    @DisplayName("ApprovalStatus 从 wire 名解析回原常量")
    void approval_status_round_trips(ApprovalStatus status) {
        assertThat(ApprovalStatus.fromWire(status.wire())).isSameAs(status);
    }

    @Test
    @DisplayName("ApprovalStatus 拒绝未知 wire 名")
    void approval_status_rejects_unknown_wire_name() {
        assertThatThrownBy(() -> ApprovalStatus.fromWire("approve"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approve");
    }

    @Test
    @DisplayName("AiErrorCode 的 wire 名是冻结的小写下划线名")
    void ai_error_code_wire_names_are_frozen() {
        assertThat(Arrays.stream(AiErrorCode.values()).map(AiErrorCode::wire))
                .containsExactly(
                        "token_invalid",
                        "token_expired",
                        "identity_mismatch",
                        "command_not_registered",
                        "command_mismatch",
                        "targets_mismatch",
                        "version_mismatch",
                        "args_hash_mismatch",
                        "action_class_mismatch",
                        "approval_missing",
                        "aud_mismatch",
                        "idempotency_key_mismatch",
                        "policy_stale",
                        "ledger_unavailable");
    }

    @ParameterizedTest
    @EnumSource(AiErrorCode.class)
    @DisplayName("AiErrorCode 从 wire 名解析回原常量")
    void ai_error_code_round_trips(AiErrorCode code) {
        assertThat(AiErrorCode.fromWire(code.wire())).isSameAs(code);
    }

    @Test
    @DisplayName("AiErrorCode 拒绝未知 wire 名")
    void ai_error_code_rejects_unknown_wire_name() {
        assertThatThrownBy(() -> AiErrorCode.fromWire("approval_not_approved"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approval_not_approved");
    }

    @Test
    @DisplayName("ActionClass 的 wire 名是冻结的小写下划线名")
    void action_class_wire_names_are_frozen() {
        assertThat(Arrays.stream(ActionClass.values()).map(ActionClass::wire))
                .containsExactly(
                        "read_only",
                        "reversible",
                        "compensable",
                        "irreversible_financial",
                        "irreversible_external");
    }

    @ParameterizedTest
    @EnumSource(ActionClass.class)
    @DisplayName("只有两个不可逆档位强制人工审批")
    void only_irreversible_action_classes_require_approval(ActionClass actionClass) {
        boolean irreversible =
                actionClass == ActionClass.IRREVERSIBLE_FINANCIAL
                        || actionClass == ActionClass.IRREVERSIBLE_EXTERNAL;

        assertThat(actionClass.requiresApproval()).isEqualTo(irreversible);
    }
}
