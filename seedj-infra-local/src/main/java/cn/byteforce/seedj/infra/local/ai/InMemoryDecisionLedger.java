package cn.byteforce.seedj.infra.local.ai;

import cn.byteforce.seedj.ai.ApprovalSnapshot;
import cn.byteforce.seedj.ai.DecisionEffect;
import cn.byteforce.seedj.ai.DecisionIntent;
import cn.byteforce.seedj.ai.DecisionRecord;
import cn.byteforce.seedj.ai.DecisionRecordBodies;
import cn.byteforce.seedj.ai.DecisionRecordKind;
import cn.byteforce.seedj.ai.LedgerHash;
import cn.byteforce.seedj.ai.LedgerVerification;
import cn.byteforce.seedj.ai.OutcomeSource;
import cn.byteforce.seedj.ai.spi.DecisionLedger;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 内存决策账本：按租户分区、每分区一把锁串行推进哈希链。
 *
 * <p>与 {@code PostgresDecisionLedger} 同语义：记录体由 {@link DecisionRecordBodies} 组装、哈希由
 * {@link LedgerHash} 计算、分区名按 {@link DecisionRecord#partitionOf(UUID)}——同一份决策在两套账本里写出同一个
 * {@code record_hash}。追加时在分区锁内取 {@code sequence = lastSequence + 1} 与 {@code prevHash = lastHash}，
 * 写记录后推进链头，因此同分区的追加串行、不同分区互不阻塞。
 *
 * <p>幂等：{@code intent} 与 {@code outcome} 每个 decision 至多一条（重复追加返回首次记录）；{@code approval} 与
 * {@code reconciled} 不幂等，每次一条。非 intent 记录的租户由已落账的 intent 反查。
 *
 * <p>状态在堆上，进程重启即丢；这是本地开发与测试的参照实现，不用于生产。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class InMemoryDecisionLedger implements DecisionLedger {

    /** 分区名 → 分区状态。 */
    private final ConcurrentHashMap<String, Partition> partitions = new ConcurrentHashMap<>();

    @Override
    public DecisionRecord appendIntent(DecisionIntent intent) {
        Objects.requireNonNull(intent, "intent");
        return append(
                partition(intent.tenantId()),
                intent.decisionId(),
                DecisionRecordKind.INTENT,
                DecisionRecordBodies.intent(intent),
                true);
    }

    @Override
    public DecisionRecord appendApproval(UUID decisionId, ApprovalSnapshot snapshot) {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(snapshot, "snapshot");
        return append(
                partition(tenantOf(decisionId)),
                decisionId,
                DecisionRecordKind.APPROVAL,
                DecisionRecordBodies.approval(snapshot),
                false);
    }

    @Override
    public DecisionRecord appendOutcome(UUID decisionId, DecisionEffect effect, OutcomeSource source) {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(source, "source");
        return append(
                partition(tenantOf(decisionId)),
                decisionId,
                DecisionRecordKind.OUTCOME,
                DecisionRecordBodies.outcome(effect, source),
                true);
    }

    @Override
    public DecisionRecord appendReconciled(UUID decisionId, DecisionEffect effect, String reason) {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(reason, "reason");
        Partition partition = partition(tenantOf(decisionId));
        partition.lock.lock();
        try {
            DecisionRecord outcome = partition.existing(decisionId, DecisionRecordKind.OUTCOME);
            String supersedes = outcome == null ? null : outcome.recordId().toString();
            return appendLocked(
                    partition,
                    decisionId,
                    DecisionRecordKind.RECONCILED,
                    DecisionRecordBodies.reconciled(effect, reason, supersedes),
                    false);
        } finally {
            partition.lock.unlock();
        }
    }

    @Override
    public Optional<DecisionRecord> find(UUID tenantId, UUID recordId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(recordId, "recordId");
        Partition partition = partitions.get(DecisionRecord.partitionOf(tenantId));
        if (partition == null) {
            return Optional.empty();
        }
        partition.lock.lock();
        try {
            return partition.records.stream()
                    .filter(record -> record.recordId().equals(recordId))
                    .findFirst();
        } finally {
            partition.lock.unlock();
        }
    }

    @Override
    public List<DecisionRecord> byDecision(UUID tenantId, UUID decisionId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(decisionId, "decisionId");
        Partition partition = partitions.get(DecisionRecord.partitionOf(tenantId));
        if (partition == null) {
            return List.of();
        }
        partition.lock.lock();
        try {
            return partition.records.stream()
                    .filter(record -> record.decisionId().equals(decisionId))
                    .toList();
        } finally {
            partition.lock.unlock();
        }
    }

    /**
     * 校对分区：<b>先取链头快照，再按链头截断扫记录</b>——与 {@code PostgresDecisionLedger#verify} 同一套判定。
     *
     * <p>{@code sequence <= lastSequence} 之外（快照之后才追加）的记录不参与校对，所以两次读之间有人追加也不会误报；
     * 记录数少于链头序号或末条 {@code record_hash} 对不上链头的哈希，就是尾部被截断或改过。
     */
    @Override
    public LedgerVerification verify(String partition) {
        Objects.requireNonNull(partition, "partition");
        Partition state = partitions.get(partition);
        if (state == null) {
            return LedgerVerification.ok(partition, 0);
        }
        state.lock.lock();
        try {
            long lastSequence = state.sequence;
            String headHash = state.lastHash;
            String expectedPrev = LedgerHash.GENESIS;
            long checked = 0;
            DecisionRecord last = null;
            for (DecisionRecord record : state.records) {
                if (record.sequence() > lastSequence) {
                    continue;
                }
                checked++;
                last = record;
                if (!record.prevHash().equals(expectedPrev)) {
                    return LedgerVerification.mismatch(
                            partition, checked, record.recordId(), expectedPrev, record.prevHash());
                }
                String expectedHash = LedgerHash.of(record);
                if (!expectedHash.equals(record.recordHash())) {
                    return LedgerVerification.mismatch(
                            partition, checked, record.recordId(), expectedHash, record.recordHash());
                }
                expectedPrev = record.recordHash();
            }
            String lastHash = last == null ? LedgerHash.GENESIS : last.recordHash();
            if (checked != lastSequence || !headHash.equals(lastHash)) {
                return LedgerVerification.mismatch(
                        partition,
                        checked,
                        last == null ? null : last.recordId(),
                        headHash,
                        lastHash);
            }
            return LedgerVerification.ok(partition, checked);
        } finally {
            state.lock.unlock();
        }
    }

    /** 清空全部分区（测试用）。 */
    void clear() {
        partitions.clear();
    }

    /** 取租户分区；分区还没写过时建一个空链。 */
    private Partition partition(UUID tenantId) {
        return partitions.computeIfAbsent(
                DecisionRecord.partitionOf(tenantId), name -> new Partition(name, tenantId));
    }

    /** 非 intent 记录的租户由已落账的 intent 反查。 */
    private UUID tenantOf(UUID decisionId) {
        for (Partition partition : partitions.values()) {
            partition.lock.lock();
            try {
                if (partition.existing(decisionId, DecisionRecordKind.INTENT) != null) {
                    return partition.tenantId;
                }
            } finally {
                partition.lock.unlock();
            }
        }
        throw new IllegalArgumentException("unknown decision: " + decisionId);
    }

    /** 在分区锁内追加一条记录；{@code idempotent} 为真时同 decision 同 kind 只留首次记录。 */
    private DecisionRecord append(
            Partition partition,
            UUID decisionId,
            DecisionRecordKind kind,
            Map<String, Object> body,
            boolean idempotent) {
        partition.lock.lock();
        try {
            return appendLocked(partition, decisionId, kind, body, idempotent);
        } finally {
            partition.lock.unlock();
        }
    }

    /** 追加记录并推进链头；调用方必须已持有 {@code partition.lock}。 */
    private DecisionRecord appendLocked(
            Partition partition,
            UUID decisionId,
            DecisionRecordKind kind,
            Map<String, Object> body,
            boolean idempotent) {
        if (idempotent) {
            DecisionRecord existing = partition.existing(decisionId, kind);
            if (existing != null) {
                return existing;
            }
        }
        long sequence = partition.sequence + 1;
        Instant recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        DecisionRecord unsigned =
                new DecisionRecord(
                        UUID.randomUUID(),
                        decisionId,
                        kind,
                        partition.name,
                        sequence,
                        partition.lastHash,
                        LedgerHash.GENESIS,
                        recordedAt,
                        partition.tenantId,
                        new LinkedHashMap<>(body));
        String recordHash = LedgerHash.of(unsigned);
        DecisionRecord record =
                new DecisionRecord(
                        unsigned.recordId(),
                        decisionId,
                        kind,
                        partition.name,
                        sequence,
                        partition.lastHash,
                        recordHash,
                        recordedAt,
                        partition.tenantId,
                        new LinkedHashMap<>(body));
        partition.records.add(record);
        partition.sequence = sequence;
        partition.lastHash = recordHash;
        return record;
    }

    /** 一个租户分区的状态：记录链、链头与串行化追加的锁。 */
    private static final class Partition {

        private final String name;

        private final UUID tenantId;

        private final ReentrantLock lock = new ReentrantLock();

        private final List<DecisionRecord> records = new ArrayList<>();

        private long sequence;

        private String lastHash = LedgerHash.GENESIS;

        private Partition(String name, UUID tenantId) {
            this.name = name;
            this.tenantId = tenantId;
        }

        /** 按 {@code (decisionId, kind)} 找已落账的记录；调用方必须已持有 {@code lock}。 */
        private DecisionRecord existing(UUID decisionId, DecisionRecordKind kind) {
            for (DecisionRecord record : records) {
                if (record.decisionId().equals(decisionId) && record.kind() == kind) {
                    return record;
                }
            }
            return null;
        }
    }
}
