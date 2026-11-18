package cn.byteforce.seedj.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 守卫结论的两条分支：放行必须带已校验 claim，拒绝必须带稳定错误码。
 *
 * <p>两条都是“结论必须自带全部信息”的约束：放行缺少 claim 会让调用方重复解 token， 拒绝缺少错误码会让 HTTP 状态与告警无从映射。
 */
class GuardVerdictTest {

    private static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant ISSUED_AT = Instant.parse("2026-11-18T00:00:00Z");

    @Test
    @DisplayName("放行结论缺少 claim 时拒绝")
    void admit_requires_claims() {
        assertThatThrownBy(() -> new GuardVerdict.Admit(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("claims");
    }

    @Test
    @DisplayName("放行结论原样携带已校验的 claim")
    void admit_carries_claims() {
        DecisionTokenClaims claims = claims();

        assertThat(new GuardVerdict.Admit(claims).claims()).isSameAs(claims);
    }

    @Test
    @DisplayName("拒绝结论缺少错误码时拒绝")
    void reject_requires_code() {
        assertThatThrownBy(() -> new GuardVerdict.Reject(null, "targets mismatch"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("code");
    }

    @Test
    @DisplayName("拒绝结论携带稳定错误码，detail 可缺省")
    void reject_carries_code_and_optional_detail() {
        GuardVerdict.Reject reject = new GuardVerdict.Reject(AiErrorCode.APPROVAL_MISSING, null);

        assertThat(reject.code().wire()).isEqualTo("approval_missing");
        assertThat(reject.detail()).isNull();
    }

    private static DecisionTokenClaims claims() {
        return new DecisionTokenClaims(
                DecisionTokenClaims.VERSION,
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                new CommandRef("ticket.create", List.of(), 7L),
                "sha256:" + "a".repeat(64),
                ACTOR,
                ACTOR,
                "tenant-1",
                ActionClass.REVERSIBLE,
                PolicyRef.none(),
                null,
                ISSUED_AT,
                ISSUED_AT.plusSeconds(60),
                "seedj-server");
    }
}
