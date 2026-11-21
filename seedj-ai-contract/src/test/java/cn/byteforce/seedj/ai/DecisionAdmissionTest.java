package cn.byteforce.seedj.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 准入凭证的值约束。 */
class DecisionAdmissionTest {

    private static final UUID DECISION = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    @Test
    @DisplayName("合法凭证归一化目标列表")
    void normalizesTargets() {
        DecisionAdmission admission =
                new DecisionAdmission(
                        DECISION,
                        TENANT,
                        "ticket.refund",
                        List.of(TARGET),
                        "sha256:a",
                        ACTOR,
                        NOW.plusSeconds(60),
                        NOW);

        assertThat(admission.targets()).containsExactly(TARGET);
    }

    @Test
    @DisplayName("空命令/空指纹/负时间：拒绝")
    void rejectsInvalidValues() {
        assertThatThrownBy(
                        () ->
                                new DecisionAdmission(
                                        DECISION,
                                        TENANT,
                                        " ",
                                        List.of(),
                                        "sha256:a",
                                        ACTOR,
                                        NOW.plusSeconds(60),
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commandType");
        assertThatThrownBy(
                        () ->
                                new DecisionAdmission(
                                        DECISION,
                                        TENANT,
                                        "ticket.refund",
                                        List.of(),
                                        " ",
                                        ACTOR,
                                        NOW.plusSeconds(60),
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("argsHash");
        assertThatThrownBy(
                        () ->
                                new DecisionAdmission(
                                        DECISION,
                                        TENANT,
                                        "ticket.refund",
                                        List.of(),
                                        "sha256:a",
                                        ACTOR,
                                        NOW.minusSeconds(1),
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expiresAt");
    }

    @Test
    @DisplayName("LedgerAudit 只有链自洽且行数等于链头才 ok")
    void ledgerAuditRequiresNoGhosts() {
        LedgerVerification ok = LedgerVerification.ok("t_x", 2);
        assertThat(new LedgerAudit("t_x", 2, 2, ok).ok()).isTrue();
        assertThat(new LedgerAudit("t_x", 3, 2, ok).ok()).isFalse();
    }
}
