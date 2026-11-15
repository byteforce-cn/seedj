package cn.byteforce.seedj.infra.coord;

import cn.byteforce.coord.sdk.kv.KvClient;
import cn.byteforce.coord.sdk.txn.TxnClient;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * coord KV + OCC 事务的组合原语：单键读、前缀扫描、条件写（CAS）。
 *
 * <p>coord 只提供 KV 与事务两个基础原语；seedj 能力（锁、投递账本、密钥目录、策略索引）需要的是 「读-判-写」原子性，因此在 {@link TxnClient}
 * 的读集校验上自建 {@link #putIfAbsent}、{@link #replace}、 {@link #removeIfMatch}。过期判定不由本类负责：调用方把 {@code
 * expiresAt} 写进记录，读时按本地时钟判断， 这样才满足 seedj 契约要求的亚秒级租约。
 *
 * <p>CAS 冲突是 OCC 的正常结果（{@code committed=false}），不是异常：最多重试 {@value #CAS_ATTEMPTS} 次后返回 {@code
 * false}。传输失败抛 {@link cn.byteforce.coord.sdk.CoordException}，由能力层统一映射。
 */
final class CoordKvStore {

    private static final int CAS_ATTEMPTS = 5;

    /** 一条 KV 记录；空值视为不存在（coord 用空值表示墓碑）。 */
    record Entry(byte[] value, long modRevision, long leaseId) {}

    private final KvClient kv;
    private final TxnClient txn;

    CoordKvStore(KvClient kv, TxnClient txn) {
        this.kv = Objects.requireNonNull(kv, "kv");
        this.txn = Objects.requireNonNull(txn, "txn");
    }

    /**
     * 单键读。
     *
     * <p>读在 coord 的 {@code safe_ts} 上，可能看不到刚提交的写：只能当作提示（快路径）， 任何「不能重复」的决定都必须经过 {@link #putIfAbsent}
     * / {@link #replace} 的事务读集校验。
     */
    Optional<Entry> get(String key) {
        KvClient.RangeResult result = kv.range(CoordKeySpace.bytes(key));
        return result.kvs().isEmpty() ? Optional.empty() : present(result.kvs().get(0));
    }

    /** 前缀扫描（按 key 字节序），最多 {@code limit} 条。 */
    List<Entry> scan(String prefix, int limit) {
        KvClient.RangeResult result =
                kv.range(
                        CoordKeySpace.bytes(prefix),
                        CoordKeySpace.rangeEndOf(prefix),
                        limit,
                        0,
                        false,
                        false);
        List<Entry> entries = new ArrayList<>(result.kvs().size());
        for (KvClient.KeyValue value : result.kvs()) {
            present(value).ifPresent(entries::add);
        }
        return entries;
    }

    void put(String key, byte[] value) {
        kv.put(CoordKeySpace.bytes(key), value);
    }

    boolean putIfAbsent(String key, byte[] value) {
        return cas(key, null, value);
    }

    boolean replace(String key, byte[] expected, byte[] value) {
        Objects.requireNonNull(expected, "expected");
        return cas(key, expected, value);
    }

    boolean removeIfMatch(String key, byte[] expected) {
        Objects.requireNonNull(expected, "expected");
        return cas(key, expected, null);
    }

    private boolean cas(String key, byte[] expected, byte[] value) {
        byte[] raw = CoordKeySpace.bytes(key);
        TxnClient.Mutation mutation =
                value == null ? TxnClient.Mutation.delete(raw) : TxnClient.Mutation.put(raw, value);
        for (int attempt = 0; attempt < CAS_ATTEMPTS; attempt++) {
            TxnClient.Session session = txn.begin();
            boolean committed = false;
            try {
                List<KvClient.KeyValue> read = session.read(raw).kvs();
                Optional<Entry> current = read.isEmpty() ? Optional.empty() : present(read.get(0));
                if (!matches(current, expected)) {
                    session.abort();
                    return false;
                }
                TxnClient.CommitResult result = session.commit(List.of(mutation));
                committed = result.committed();
                if (committed) {
                    return true;
                }
            } finally {
                if (!committed) {
                    session.abort();
                }
            }
        }
        return false;
    }

    private static boolean matches(Optional<Entry> current, byte[] expected) {
        if (expected == null) {
            return current.isEmpty();
        }
        return current.isPresent() && Arrays.equals(current.get().value(), expected);
    }

    private static Optional<Entry> present(KvClient.KeyValue value) {
        if (value.value() == null || value.value().length == 0) {
            return Optional.empty();
        }
        return Optional.of(new Entry(value.value(), value.modRevision(), value.leaseId()));
    }
}
