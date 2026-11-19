package cn.byteforce.seedj.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 外部效果值类型：状态推进与 fail-closed 校验。 */
class ExternalEffectTest {

    private static final UUID DECISION = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    @Test
    @DisplayName("prepared → confirmed → failed 状态只前进")
    void stateTransitions() {
        ExternalEffect prepared =
                ExternalEffect.prepared(DECISION, TENANT, "billing.refund", "sha256:a", NOW);
        ExternalEffect confirmed = prepared.confirmed("ext-1", NOW.plusSeconds(1));
        ExternalEffect failed = confirmed.failed("BOOM", NOW.plusSeconds(2));

        assertThat(prepared.state()).isEqualTo(ExternalEffectState.PREPARED);
        assertThat(confirmed.externalRef()).isEqualTo("ext-1");
        assertThat(failed.errorCode()).isEqualTo("BOOM");
        assertThat(failed.decisionId()).isEqualTo(DECISION);
    }

    @Test
    @DisplayName("CONFIRMED 缺 externalRef/completedAt：拒绝")
    void confirmedRequiresReferenceAndTime() {
        assertThatThrownBy(
                        () ->
                                new ExternalEffect(
                                        DECISION,
                                        TENANT,
                                        "billing.refund",
                                        "sha256:a",
                                        ExternalEffectState.CONFIRMED,
                                        null,
                                        null,
                                        NOW,
                                        null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("externalRef");
        assertThatThrownBy(
                        () ->
                                new ExternalEffect(
                                        DECISION,
                                        TENANT,
                                        "billing.refund",
                                        "sha256:a",
                                        ExternalEffectState.CONFIRMED,
                                        "ext-1",
                                        null,
                                        NOW,
                                        null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("completedAt");
    }

    @Test
    @DisplayName("FAILED 缺 errorCode：拒绝")
    void failedRequiresErrorCode() {
        assertThatThrownBy(
                        () ->
                                new ExternalEffect(
                                        DECISION,
                                        TENANT,
                                        "billing.refund",
                                        "sha256:a",
                                        ExternalEffectState.FAILED,
                                        null,
                                        null,
                                        NOW,
                                        NOW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("errorCode");
    }

    @Test
    @DisplayName("PREPARED 带完成字段：拒绝")
    void preparedMustNotCarryCompletion() {
        assertThatThrownBy(
                        () ->
                                new ExternalEffect(
                                        DECISION,
                                        TENANT,
                                        "billing.refund",
                                        "sha256:a",
                                        ExternalEffectState.PREPARED,
                                        "ext-1",
                                        null,
                                        NOW,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PREPARED");
    }

    @Test
    @DisplayName("状态线格式往返，未知值拒绝")
    void stateWireRoundTrip() {
        for (ExternalEffectState state : ExternalEffectState.values()) {
            assertThat(ExternalEffectState.fromWire(state.wire())).isSameAs(state);
        }
        assertThatThrownBy(() -> ExternalEffectState.fromWire("done"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("done");
    }
}
