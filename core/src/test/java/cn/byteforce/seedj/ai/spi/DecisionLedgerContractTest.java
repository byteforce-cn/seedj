package cn.byteforce.seedj.ai.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.ai.ActionClass;
import cn.byteforce.seedj.ai.AgentIdentity;
import cn.byteforce.seedj.ai.ApprovalSnapshot;
import cn.byteforce.seedj.ai.ApprovalStatus;
import cn.byteforce.seedj.ai.CommandRef;
import cn.byteforce.seedj.ai.DecisionEffect;
import cn.byteforce.seedj.ai.DecisionIntent;
import cn.byteforce.seedj.ai.DecisionKind;
import cn.byteforce.seedj.ai.DecisionRecord;
import cn.byteforce.seedj.ai.DecisionRecordKind;
import cn.byteforce.seedj.ai.Evidence;
import cn.byteforce.seedj.ai.ModelRef;
import cn.byteforce.seedj.ai.OutcomeSource;
import cn.byteforce.seedj.ai.PolicyRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 决策账本的契约测试：任何 {@link DecisionLedger} 实现都必须满足这批行为。
 *
 * <p>子类放在实现模块（内存、PostgreSQL），位于 {@code extends} 位置；{@code core} 自身不实例化本类。 契约只约束可观察行为，不约束存储形态：
 * intent/outcome 每个 decision 恰一条、同一分区内序列严格递增且哈希链连续、 记录只追加、跨 tenant 查询不泄露存在性。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public abstract class DecisionLedgerContractTest {

    /** 契约用例的租户：每个用例用自己的租户，互不干扰。 */
    protected static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    /** 另一个租户，用于验证 tenant 作用域。 */
    protected static final UUID OTHER_TENANT =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID ACTOR = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ON_BEHALF_OF = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID TASK_CONTRACT =
            UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID CONVERSATION = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final Instant RECORDED_AT = Instant.parse("2026-11-18T00:00:00Z");

    /** 被测账本：由子类在 {@link #ledger()} 返回同一个实例（不要在方法里新建）。 */
    protected abstract DecisionLedger ledger();

    /** 清空同一实例的全部状态，供每个用例从空账本开始。 */
    protected abstract void reset();

    @BeforeEach
    void resetLedger() {
        reset();
    }

    @Test
    @DisplayName("同一 decision 重复 append intent 是幂等的：只留一条记录")
    void intent_is_unique_per_decision() {
        DecisionIntent intent = intent(TENANT, decision(1));

        DecisionRecord first = ledger().appendIntent(intent);
        DecisionRecord second = ledger().appendIntent(intent);

        assertThat(first.kind()).isEqualTo(DecisionRecordKind.INTENT);
        assertThat(second.recordId()).isEqualTo(first.recordId());
        assertThat(ledger().byDecision(TENANT, intent.decisionId())).hasSize(1);
    }

    @Test
    @DisplayName("同一 decision 重复 append outcome 是幂等的：只留一条 outcome 记录")
    void outcome_is_unique_per_decision() {
        DecisionIntent intent = intent(TENANT, decision(2));
        ledger().appendIntent(intent);
        DecisionEffect effect = DecisionEffect.ok("audit-1", UUID.randomUUID());

        DecisionRecord first =
                ledger().appendOutcome(
                        intent.decisionId(), effect, OutcomeSource.RUNTIME_REPORTED);
        DecisionRecord second =
                ledger().appendOutcome(intent.decisionId(), effect, OutcomeSource.RECONCILED);

        assertThat(second.recordId()).isEqualTo(first.recordId());
        List<DecisionRecord> records = ledger().byDecision(TENANT, intent.decisionId());
        assertThat(records)
                .extracting(DecisionRecord::kind)
                .containsExactly(DecisionRecordKind.INTENT, DecisionRecordKind.OUTCOME);
    }

    @Test
    @DisplayName("同一分区内 append 串行：sequence 递增且 prev_hash 接上前一条 record_hash")
    void append_is_serial_per_partition() throws Exception {
        DecisionIntent first = intent(TENANT, decision(3));
        DecisionIntent second = intent(TENANT, decision(4));

        List<DecisionRecord> records = appendConcurrently(first, second);

        assertThat(records).extracting(DecisionRecord::sequence).containsExactly(1L, 2L);
        assertThat(records.get(1).prevHash()).isEqualTo(records.get(0).recordHash());
        assertThat(records.get(0).partition()).isEqualTo(records.get(1).partition());
        assertThat(ledger().verify(records.get(0).partition()).ok()).isTrue();
    }

    @Test
    @DisplayName("账本只追加：SPI 上没有 update/delete/remove 入口")
    void records_are_append_only() {
        List<String> mutating =
                List.of(DecisionLedger.class.getMethods()).stream()
                        .map(method -> method.getName().toLowerCase(Locale.ROOT))
                        .filter(
                                name ->
                                        name.contains("update")
                                                || name.contains("delete")
                                                || name.contains("remove"))
                        .toList();

        assertThat(mutating).as("账本 SPI 不得暴露修改/删除入口").isEmpty();
        assertThat(ledger().byDecision(TENANT, decision(5))).isEmpty();
    }

    @Test
    @DisplayName("by_decision 按 tenant 作用域：别的租户查同一 decision 得到空列表")
    void by_decision_is_tenant_scoped() {
        DecisionIntent intent = intent(TENANT, decision(6));
        DecisionRecord record = ledger().appendIntent(intent);

        assertThat(ledger().byDecision(OTHER_TENANT, intent.decisionId())).isEmpty();
        assertThat(ledger().find(OTHER_TENANT, record.recordId())).isEmpty();
        assertThat(ledger().find(TENANT, record.recordId())).isPresent();
    }

    @Test
    @DisplayName("跨租户复用同一 decision_id 一律拒绝：不返回别人的记录，也不写影子记录")
    void reusing_decision_id_in_another_tenant_is_rejected() {
        DecisionIntent owned = intent(TENANT, decision(11));
        DecisionRecord record = ledger().appendIntent(owned);

        DecisionIntent reused = intent(OTHER_TENANT, owned.decisionId());

        assertThatThrownBy(() -> ledger().appendIntent(reused))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another tenant");
        assertThat(ledger().byDecision(OTHER_TENANT, owned.decisionId())).isEmpty();
        assertThat(ledger().byDecision(TENANT, owned.decisionId()))
                .extracting(DecisionRecord::recordId)
                .containsExactly(record.recordId());
    }

    @Test
    @DisplayName("分区名按租户规则：t_<tenantId>")
    void partition_follows_tenant_rule() {
        DecisionRecord record = ledger().appendIntent(intent(TENANT, decision(7)));

        assertThat(record.partition()).isEqualTo(DecisionRecord.partitionOf(TENANT));
    }

    @Test
    @DisplayName("同 decisionId 内容不同的第二个 intent 也是幂等的：保留首次记录")
    void intent_idempotent_even_with_different_content() {
        DecisionIntent first = intent(TENANT, decision(8), 0, "第一次判断");
        DecisionIntent second = intent(TENANT, decision(8), 3, "改了理由的第二次判断");

        DecisionRecord firstRecord = ledger().appendIntent(first);
        DecisionRecord secondRecord = ledger().appendIntent(second);

        assertThat(secondRecord.recordId()).isEqualTo(firstRecord.recordId());
        assertThat(ledger().byDecision(TENANT, first.decisionId()))
                .extracting(DecisionRecord::kind)
                .containsExactly(DecisionRecordKind.INTENT);
    }

    @Test
    @DisplayName("append_approval 要求 intent 已存在：否则 IllegalArgumentException")
    void append_approval_requires_existing_intent() {
        assertThatThrownBy(() -> ledger().appendApproval(decision(9), approvalSnapshot()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("approval 与 reconciled 不幂等：每个 decision 可多条，sequence 连续")
    void approval_and_reconciled_are_not_idempotent() {
        DecisionIntent intent = intent(TENANT, decision(10));
        ledger().appendIntent(intent);

        ledger().appendApproval(intent.decisionId(), approvalSnapshot());
        ledger().appendApproval(intent.decisionId(), approvalSnapshot());
        ledger().appendOutcome(
                intent.decisionId(), DecisionEffect.unknown(), OutcomeSource.RUNTIME_REPORTED);
        ledger().appendReconciled(intent.decisionId(), DecisionEffect.unknown(), "第一次核对");
        ledger().appendReconciled(intent.decisionId(), DecisionEffect.unknown(), "第二次核对");

        List<DecisionRecord> records = ledger().byDecision(TENANT, intent.decisionId());
        assertThat(records)
                .extracting(DecisionRecord::kind)
                .containsExactly(
                        DecisionRecordKind.INTENT,
                        DecisionRecordKind.APPROVAL,
                        DecisionRecordKind.APPROVAL,
                        DecisionRecordKind.OUTCOME,
                        DecisionRecordKind.RECONCILED,
                        DecisionRecordKind.RECONCILED);
        assertThat(records)
                .extracting(DecisionRecord::sequence)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(ledger().verify(DecisionRecord.partitionOf(TENANT)).ok()).isTrue();
    }

    /**
     * 跨租户并发复用同一 {@code decision_id}：恰好一个成功、另一个被拒，且成功方拿到的永远是自己租户的记录。
     *
     * <p>这条用例是<b>概率性回归护栏</b>：修复前 PG 的「事务内查到的既有记录」出口没过租户校验，两个并发事务在 READ
     * COMMITTED 下能互相看到对方刚提交的行，于是两边都拿到同一条记录。单轮命中率取决于线程交错，所以重复多轮。
     */
    @Test
    @DisplayName("跨租户并发复用同一 decision_id：恰好一个成功，另一个被拒，绝不返回别人的记录")
    void concurrent_cross_tenant_same_decision_never_leaks() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 1; round <= 20; round++) {
                UUID decisionId = decision(60 + round);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Attempt>> futures =
                        List.of(
                                executor.submit(
                                        () -> attempt(start, intent(TENANT, decisionId))),
                                executor.submit(
                                        () -> attempt(start, intent(OTHER_TENANT, decisionId))));
                start.countDown();
                List<Attempt> attempts = new ArrayList<>();
                for (Future<Attempt> future : futures) {
                    attempts.add(future.get(30, TimeUnit.SECONDS));
                }

                List<Attempt> accepted = attempts.stream().filter(Attempt::isAccepted).toList();
                List<Attempt> rejected = attempts.stream().filter(Attempt::isRejected).toList();
                assertThat(accepted).as("第 %d 轮：恰好一个线程成功", round).hasSize(1);
                assertThat(rejected).as("第 %d 轮：另一个必须被拒", round).hasSize(1);
                assertThat(rejected.get(0).rejection())
                        .hasMessageContaining("another tenant")
                        .hasMessageContaining(decisionId.toString());
                assertThat(accepted.get(0).record().tenantId())
                        .as("第 %d 轮：不得把别人的记录当幂等结果返回", round)
                        .isEqualTo(accepted.get(0).tenantId());
                assertThat(
                                ledger().byDecision(TENANT, decisionId).size()
                                        + ledger().byDecision(OTHER_TENANT, decisionId).size())
                        .as("第 %d 轮：全局只落一条 intent", round)
                        .isEqualTo(1);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /** 一次并发尝试：成功拿到记录，或按契约被拒绝，二者恰有其一。 */
    private record Attempt(UUID tenantId, DecisionRecord record, IllegalArgumentException rejection) {

        private static Attempt accepted(UUID tenantId, DecisionRecord record) {
            return new Attempt(tenantId, record, null);
        }

        private static Attempt rejected(UUID tenantId, IllegalArgumentException rejection) {
            return new Attempt(tenantId, null, rejection);
        }

        private boolean isAccepted() {
            return record != null;
        }

        private boolean isRejected() {
            return rejection != null;
        }
    }

    /** 等到同时放行后在同一 {@code decision_id} 上用自己的租户追加 intent。 */
    private Attempt attempt(CountDownLatch start, DecisionIntent intent) throws InterruptedException {
        start.await();
        try {
            return Attempt.accepted(intent.tenantId(), ledger().appendIntent(intent));
        } catch (IllegalArgumentException rejected) {
            return Attempt.rejected(intent.tenantId(), rejected);
        }
    }

    /** 已批准的审批快照：实现侧用例可复用。 */
    protected static ApprovalSnapshot approvalSnapshot() {
        return new ApprovalSnapshot(
                "wf-instance",
                "wf-task",
                ApprovalStatus.APPROVED,
                RECORDED_AT,
                ACTOR,
                RECORDED_AT,
                "sha256:" + "a".repeat(64),
                1L);
    }

    /** 两个线程同时 append，返回按 sequence 升序的结果。 */
    private List<DecisionRecord> appendConcurrently(DecisionIntent first, DecisionIntent second)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<DecisionRecord>> futures = new ArrayList<>();
            for (DecisionIntent intent : List.of(first, second)) {
                futures.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    return ledger().appendIntent(intent);
                                }));
            }
            start.countDown();
            List<DecisionRecord> records = new ArrayList<>();
            for (Future<DecisionRecord> future : futures) {
                records.add(future.get(30, TimeUnit.SECONDS));
            }
            records.sort((left, right) -> Long.compare(left.sequence(), right.sequence()));
            return records;
        } finally {
            executor.shutdownNow();
        }
    }

    /** 构造一个合法的 EXECUTE_TOOL intent；实现侧用例可复用。 */
    protected static DecisionIntent intent(UUID tenantId, UUID decisionId) {
        return intent(tenantId, decisionId, 0, "用户要求创建工单");
    }

    /** 带自定步号与理由的 EXECUTE_TOOL intent，用于验证内容不同时的幂等口径。 */
    protected static DecisionIntent intent(
            UUID tenantId, UUID decisionId, int step, String rationale) {
        return new DecisionIntent(
                decisionId,
                tenantId,
                TASK_CONTRACT,
                CONVERSATION,
                step,
                DecisionKind.EXECUTE_TOOL,
                ActionClass.REVERSIBLE,
                new CommandRef(
                        "ticket.create",
                        List.of(UUID.fromString("77777777-7777-7777-7777-777777777777")),
                        7L),
                "sha256:" + "a".repeat(64),
                "b".repeat(64),
                Map.of("title", "t"),
                rationale,
                List.of(new Evidence("message", "im://conversation/6666", "c".repeat(64))),
                new ModelRef("seedj-agent", "2026-11", "prompt-v1", "d".repeat(64)),
                PolicyRef.none(),
                new AgentIdentity(ACTOR, ON_BEHALF_OF, null, "seedj-ai-starter"),
                null);
    }

    /** 用例内的 decision 标识，按序号生成，避免用例之间撞号。 */
    protected static UUID decision(int index) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", index));
    }
}
