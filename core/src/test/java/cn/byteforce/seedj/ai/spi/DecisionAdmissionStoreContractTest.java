package cn.byteforce.seedj.ai.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.ai.DecisionAdmission;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 准入存储的共享契约：内存与 PostgreSQL 两套实现都必须满足。 */
public abstract class DecisionAdmissionStoreContractTest {

    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    protected abstract DecisionAdmissionStore store();

    @Test
    @DisplayName("同决策同字段幂等")
    void admissionIsIdempotent() {
        DecisionAdmission admission = admission(UUID.randomUUID(), "ticket.refund", "sha256:a");

        store().admit(admission);
        store().admit(admission);

        assertThat(store().find(admission.decisionId())).contains(admission);
    }

    @Test
    @DisplayName("同决策不同命令冲突")
    void conflictingAdmissionIsRejected() {
        DecisionAdmission first = admission(UUID.randomUUID(), "ticket.refund", "sha256:a");
        store().admit(first);

        assertThatThrownBy(
                        () ->
                                store().admit(
                                                new DecisionAdmission(
                                                        first.decisionId(),
                                                        first.tenantId(),
                                                        "ticket.payout",
                                                        first.targets(),
                                                        first.argsHash(),
                                                        first.actor(),
                                                        first.expiresAt(),
                                                        first.admittedAt())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admission conflict");
    }

    @Test
    @DisplayName("deleteExpired 只删早于 before 的行")
    void deleteExpiredRemovesOnlyOldRows() {
        DecisionAdmission old =
                new DecisionAdmission(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "ticket.refund",
                        List.of(UUID.randomUUID()),
                        "sha256:a",
                        UUID.randomUUID(),
                        NOW.minusSeconds(60),
                        NOW.minusSeconds(120));
        DecisionAdmission fresh = admission(UUID.randomUUID(), "ticket.refund", "sha256:b");
        store().admit(old);
        store().admit(fresh);

        assertThat(store().deleteExpired(NOW)).isGreaterThanOrEqualTo(1);
        assertThat(store().find(old.decisionId())).isEmpty();
        assertThat(store().find(fresh.decisionId())).contains(fresh);
    }

    protected static DecisionAdmission admission(UUID decisionId, String command, String argsHash) {
        return new DecisionAdmission(
                decisionId,
                UUID.fromString("018f2e3a-0000-7000-8000-0000000b0007"),
                command,
                List.of(UUID.fromString("018f2e3a-0000-7000-8000-000000007731")),
                argsHash,
                UUID.fromString("018f2e3a-0000-7000-8000-0000000a9e01"),
                NOW.plusSeconds(300),
                NOW);
    }
}
