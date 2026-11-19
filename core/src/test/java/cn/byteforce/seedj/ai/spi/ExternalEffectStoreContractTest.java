package cn.byteforce.seedj.ai.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.ai.ExternalEffect;
import cn.byteforce.seedj.ai.ExternalEffectState;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 外部效果存储契约：prepare 幂等、confirmed 不再调用、complete 只前进。 */
public abstract class ExternalEffectStoreContractTest {

    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    protected abstract ExternalEffectStore store();

    @Test
    @DisplayName("prepare 幂等，complete 后可读回")
    void prepareThenComplete() {
        ExternalEffect prepared = prepared(UUID.randomUUID());

        assertThat(store().prepare(prepared)).isEqualTo(prepared);
        ExternalEffect confirmed =
                store().complete(
                                prepared.decisionId(),
                                ExternalEffectState.CONFIRMED,
                                "ext-1",
                                null,
                                NOW.plusSeconds(1));

        assertThat(confirmed.state()).isEqualTo(ExternalEffectState.CONFIRMED);
        assertThat(confirmed.externalRef()).isEqualTo("ext-1");
        assertThat(store().find(prepared.decisionId())).contains(confirmed);
    }

    @Test
    @DisplayName("已确认后 prepare 返回既有，不重新固化")
    void confirmedRetryReturnsExisting() {
        ExternalEffect prepared = prepared(UUID.randomUUID());
        store().prepare(prepared);
        store().complete(
                        prepared.decisionId(),
                        ExternalEffectState.CONFIRMED,
                        "ext-1",
                        null,
                        NOW.plusSeconds(1));

        ExternalEffect again = store().prepare(prepared);

        assertThat(again.state()).isEqualTo(ExternalEffectState.CONFIRMED);
        assertThat(again.externalRef()).isEqualTo("ext-1");
    }

    @Test
    @DisplayName("同决策不同参数：冲突")
    void conflictingPrepareIsRejected() {
        ExternalEffect prepared = prepared(UUID.randomUUID());
        store().prepare(prepared);

        assertThatThrownBy(
                        () ->
                                store().prepare(
                                                new ExternalEffect(
                                                        prepared.decisionId(),
                                                        prepared.tenantId(),
                                                        "ticket.payout",
                                                        prepared.argsHash(),
                                                        ExternalEffectState.PREPARED,
                                                        null,
                                                        null,
                                                        NOW,
                                                        null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("external effect conflict");
    }

    @Test
    @DisplayName("complete 未知决策：拒绝")
    void completeUnknownDecisionIsRejected() {
        assertThatThrownBy(
                        () ->
                                store().complete(
                                                UUID.randomUUID(),
                                                ExternalEffectState.FAILED,
                                                null,
                                                "boom",
                                                NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    protected static ExternalEffect prepared(UUID decisionId) {
        return ExternalEffect.prepared(
                decisionId,
                UUID.fromString("018f2e3a-0000-7000-8000-0000000b0007"),
                "billing.refund",
                "sha256:a",
                NOW);
    }
}
