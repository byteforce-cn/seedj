package cn.byteforce.seedj.infra.local.audit;

import cn.byteforce.seedj.audit.AuditEntry;
import cn.byteforce.seedj.audit.CanonicalHasher;
import cn.byteforce.seedj.audit.spi.AuditStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 内存审计存储：只用于开发与测试。
 *
 * <p>语义与 JDBC 实现一致：{@code nextSequenceAndPrevHash} 不消耗序号，
 * {@code updateHead} 才推进；回滚场景由调用方不调用 {@code updateHead} 表达。</p>
 */
public final class InMemoryAuditStore implements AuditStore<String> {

    private final List<AuditEntry<String>> entries = new ArrayList<>();

    private long sequence;

    private String head = CanonicalHasher.GENESIS_PREV_HASH;

    @Override
    public synchronized Head nextSequenceAndPrevHash() {
        return new Head(sequence + 1, head);
    }

    @Override
    public synchronized void insert(AuditEntry<String> entry) {
        entries.add(entry);
    }

    @Override
    public synchronized void updateHead(String hash) {
        sequence++;
        head = hash;
    }

    @Override
    public synchronized List<AuditEntry<String>> loadAll() {
        return List.copyOf(entries);
    }

    @Override
    public synchronized Head head() {
        return new Head(sequence, head);
    }
}
