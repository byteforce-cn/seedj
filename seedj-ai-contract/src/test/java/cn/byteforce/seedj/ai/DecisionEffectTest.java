package cn.byteforce.seedj.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 执行结局的工厂契约：每个工厂只填自己那一档的字段，其余留空。
 *
 * <p>{@code ok(auditEntryId, commandId)} 的参数顺序是外部调用方最容易接反的一处：审计链条目标识在前、
 * 命令记录标识在后，接反后“从审计链反查命令”整条路走不通。
 */
class DecisionEffectTest {

    private static final String AUDIT_ENTRY_ID = "audit-entry-1";
    private static final UUID COMMAND_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    @DisplayName("ok 把审计条目放 auditEntryId、命令标识放 commandId")
    void ok_carries_audit_entry_and_command_ids() {
        DecisionEffect effect = DecisionEffect.ok(AUDIT_ENTRY_ID, COMMAND_ID);

        assertThat(effect.status()).isEqualTo(EffectStatus.OK);
        assertThat(effect.auditEntryId()).isEqualTo(AUDIT_ENTRY_ID);
        assertThat(effect.commandId()).isEqualTo(COMMAND_ID);
        assertThat(effect.externalRef()).isNull();
        assertThat(effect.errorCode()).isNull();
        assertThat(effect.completedAt()).isNull();
    }

    @Test
    @DisplayName("notApplicable 不携带任何引用")
    void not_applicable_carries_no_reference() {
        DecisionEffect effect = DecisionEffect.notApplicable();

        assertThat(effect.status()).isEqualTo(EffectStatus.NOT_APPLICABLE);
        assertThat(effect.externalRef()).isNull();
        assertThat(effect.auditEntryId()).isNull();
        assertThat(effect.commandId()).isNull();
        assertThat(effect.errorCode()).isNull();
        assertThat(effect.completedAt()).isNull();
    }

    @Test
    @DisplayName("notIssued 只携带拒绝错误码")
    void not_issued_carries_error_code() {
        DecisionEffect effect = DecisionEffect.notIssued(AiErrorCode.APPROVAL_MISSING.wire());

        assertThat(effect.status()).isEqualTo(EffectStatus.NOT_ISSUED);
        assertThat(effect.errorCode()).isEqualTo("approval_missing");
        assertThat(effect.auditEntryId()).isNull();
        assertThat(effect.commandId()).isNull();
        assertThat(effect.completedAt()).isNull();
    }

    @Test
    @DisplayName("rejected 只携带业务错误码")
    void rejected_carries_error_code() {
        DecisionEffect effect = DecisionEffect.rejected("ticket_locked");

        assertThat(effect.status()).isEqualTo(EffectStatus.REJECTED);
        assertThat(effect.errorCode()).isEqualTo("ticket_locked");
        assertThat(effect.auditEntryId()).isNull();
        assertThat(effect.commandId()).isNull();
        assertThat(effect.completedAt()).isNull();
    }

    @Test
    @DisplayName("failed 只携带失败错误码")
    void failed_carries_error_code() {
        DecisionEffect effect = DecisionEffect.failed("db_unavailable");

        assertThat(effect.status()).isEqualTo(EffectStatus.FAILED);
        assertThat(effect.errorCode()).isEqualTo("db_unavailable");
        assertThat(effect.auditEntryId()).isNull();
        assertThat(effect.commandId()).isNull();
        assertThat(effect.completedAt()).isNull();
    }

    @Test
    @DisplayName("unknown 不携带错误码：结果未观测到不等于失败")
    void unknown_carries_no_error_code() {
        DecisionEffect effect = DecisionEffect.unknown();

        assertThat(effect.status()).isEqualTo(EffectStatus.UNKNOWN);
        assertThat(effect.errorCode()).isNull();
        assertThat(effect.externalRef()).isNull();
        assertThat(effect.completedAt()).isNull();
    }

    @Test
    @DisplayName("结局状态必填")
    void status_is_mandatory() {
        assertThatThrownBy(() -> new DecisionEffect(null, null, null, null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("status");
    }
}
