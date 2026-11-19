package cn.byteforce.seedj.infra.local.ai;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.ai.ApprovalSnapshot;
import cn.byteforce.seedj.ai.ApprovalStatus;
import cn.byteforce.seedj.ai.DecisionEffect;
import cn.byteforce.seedj.ai.OutcomeSource;
import cn.byteforce.seedj.ai.spi.DecisionLedger;
import cn.byteforce.seedj.ai.spi.DecisionLedgerContractTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 内存决策账本跑公共契约套件：与 PostgreSQL 账本语义一致的那批行为。
 *
 * <p>账本实例在字段里只建一次，{@link #reset()} 调包的 {@code clear()} 清空全部分区，让每个用例从空账本开始。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class InMemoryDecisionLedgerTest extends DecisionLedgerContractTest {

    private final InMemoryDecisionLedger ledger = new InMemoryDecisionLedger();

    @Override
    protected DecisionLedger ledger() {
        return ledger;
    }

    @Override
    protected void reset() {
        ledger.clear();
    }

    @Test
    @DisplayName("pendingOutcomes 只列有 intent 无 outcome 的决策")
    void pendingOutcomesFindsIntentWithoutOutcome() {
        UUID pending = decision(101);
        UUID done = decision(102);
        ledger.appendIntent(intent(TENANT, pending));
        ledger.appendIntent(intent(TENANT, done));
        ledger.appendOutcome(done, DecisionEffect.ok(null, null), OutcomeSource.RUNTIME_REPORTED);

        assertThat(ledger.pendingOutcomes(TENANT, Instant.now().plusSeconds(1), 10))
                .containsExactly(pending);
    }

    @Test
    @DisplayName("pendingAudit 只列 outcome=ok 且未链校验的决策")
    void pendingAuditFindsOkWithoutAuditEntry() {
        UUID decision = decision(103);
        ledger.appendIntent(intent(TENANT, decision));
        ledger.appendOutcome(
                decision, DecisionEffect.ok(null, null), OutcomeSource.RUNTIME_REPORTED);

        assertThat(ledger.pendingAudit(TENANT, Instant.now().plusSeconds(1), 10))
                .containsExactly(decision);
    }

    @Test
    @DisplayName("pendingApprovals 只看最新一条 approval 且仍为 pending")
    void pendingApprovalsOnlyReturnsLatestPending() {
        UUID pending = decision(104);
        UUID approved = decision(105);
        ledger.appendIntent(intent(TENANT, pending));
        ledger.appendIntent(intent(TENANT, approved));
        ledger.appendApproval(pending, approval(ApprovalStatus.PENDING));
        ledger.appendApproval(approved, approval(ApprovalStatus.PENDING));
        ledger.appendApproval(
                approved,
                new ApprovalSnapshot(
                        "i-2",
                        "t-2",
                        ApprovalStatus.APPROVED,
                        Instant.now().minusSeconds(10),
                        UUID.randomUUID(),
                        Instant.now(),
                        "sha256:a",
                        null));

        assertThat(ledger.pendingApprovals(TENANT, Instant.now().plusSeconds(1), 10))
                .containsExactly(pending);
    }

    @Test
    @DisplayName("audit：正常链的行数等于链头")
    void auditReportsNoGhostsForHealthyChain() {
        ledger.appendIntent(intent(TENANT, decision(106)));

        assertThat(ledger.audit(cn.byteforce.seedj.ai.DecisionRecord.partitionOf(TENANT)).ok())
                .isTrue();
        assertThat(
                        ledger.audit(cn.byteforce.seedj.ai.DecisionRecord.partitionOf(TENANT))
                                .rowCount())
                .isEqualTo(1);
    }

    private static ApprovalSnapshot approval(ApprovalStatus status) {
        return new ApprovalSnapshot(
                "i-1", "t-1", status, Instant.now().minusSeconds(10), null, null, "sha256:a", null);
    }
}
