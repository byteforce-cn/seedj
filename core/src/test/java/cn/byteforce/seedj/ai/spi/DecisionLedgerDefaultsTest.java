package cn.byteforce.seedj.ai.spi;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.ai.LedgerVerification;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 账本默认扫描方法：不实现时为空，audit 用 verify 的记录数兜底。 */
class DecisionLedgerDefaultsTest {

    private static final DecisionLedger EMPTY =
            new DecisionLedger() {
                @Override
                public cn.byteforce.seedj.ai.DecisionRecord appendIntent(
                        cn.byteforce.seedj.ai.DecisionIntent intent) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public cn.byteforce.seedj.ai.DecisionRecord appendApproval(
                        UUID decisionId, cn.byteforce.seedj.ai.ApprovalSnapshot snapshot) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public cn.byteforce.seedj.ai.DecisionRecord appendOutcome(
                        UUID decisionId,
                        cn.byteforce.seedj.ai.DecisionEffect effect,
                        cn.byteforce.seedj.ai.OutcomeSource source) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public cn.byteforce.seedj.ai.DecisionRecord appendReconciled(
                        UUID decisionId,
                        cn.byteforce.seedj.ai.DecisionEffect effect,
                        String reason) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public java.util.Optional<cn.byteforce.seedj.ai.DecisionRecord> find(
                        UUID tenantId, UUID recordId) {
                    return java.util.Optional.empty();
                }

                @Override
                public java.util.List<cn.byteforce.seedj.ai.DecisionRecord> byDecision(
                        UUID tenantId, UUID decisionId) {
                    return java.util.List.of();
                }

                @Override
                public LedgerVerification verify(String partition) {
                    return LedgerVerification.ok(partition, 4);
                }
            };

    @Test
    @DisplayName("默认扫描为空，audit 行数取 verify 的记录数")
    void defaultsAreEmpty() {
        UUID tenant = UUID.randomUUID();
        Instant now = Instant.now();
        assertThat(EMPTY.pendingOutcomes(tenant, now, 10)).isEmpty();
        assertThat(EMPTY.pendingAudit(tenant, now, 10)).isEmpty();
        assertThat(EMPTY.pendingApprovals(tenant, now, 10)).isEmpty();
        assertThat(EMPTY.audit("t_x").ok()).isTrue();
        assertThat(EMPTY.audit("t_x").rowCount()).isEqualTo(4);
    }
}
