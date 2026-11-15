package cn.byteforce.seedj.infra.coord.fixture;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;
import cn.byteforce.coord.sdk.kv.KvClient;
import cn.byteforce.coord.sdk.lease.LeaseClient;
import cn.byteforce.coord.sdk.txn.TxnClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 内存版 coord KV/Txn/Lease：只实现 seedj 组合层用到的语义。
 *
 * <p>刻意与真集群对齐的三点：未提交的写不可见、读集变化时提交返回 {@code committed=false}（不抛异常）、 空值视为不存在。真集群行为由 {@code
 * CoordCluster} 夹具的用例守护；这个 fake 只负责让亚秒级计时器与 冲突路径可以被确定性地测到。
 */
public final class FakeCoordBackend {

    private final NavigableMap<Bytes, Cell> data = new TreeMap<>();
    private final Map<Long, Long> leases = new HashMap<>();

    private long revision;
    private long leaseSeq;
    private boolean down;

    public KvClient kv() {
        return new FakeKv();
    }

    public TxnClient txn() {
        return new FakeTxn();
    }

    public LeaseClient lease() {
        return new FakeLease();
    }

    /** 模拟 agent 不可达：所有调用抛 {@code UNAVAILABLE}。 */
    public void down(boolean value) {
        down = value;
    }

    /** 直接写入记录（用于构造过期/损坏的既有状态）。 */
    public void seed(String key, String value) {
        data.put(bytes(key), new Cell(raw(value), nextRevision(), 0));
    }

    public String read(String key) {
        Cell cell = data.get(bytes(key));
        return cell == null ? null : new String(cell.value(), StandardCharsets.UTF_8);
    }

    private long nextRevision() {
        return ++revision;
    }

    private void failIfDown() {
        if (down) {
            throw new CoordException(ErrorCode.UNAVAILABLE, "fake coord agent is down");
        }
    }

    private static Bytes bytes(String value) {
        return new Bytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] raw(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Cell(byte[] value, long modRevision, long leaseId) {}

    /** 无符号字节序的 key；与 coord KV 的排序一致。 */
    record Bytes(byte[] raw) implements Comparable<Bytes> {

        @Override
        public int compareTo(Bytes other) {
            return Arrays.compareUnsigned(raw, other.raw);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Bytes bytes && Arrays.equals(raw, bytes.raw);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(raw);
        }
    }

    private record Range(Bytes start, Bytes end) {}

    private KvClient.KeyValue keyValue(Bytes key, Cell cell, boolean keysOnly) {
        return new KvClient.KeyValue(
                key.raw(),
                keysOnly ? null : cell.value(),
                cell.modRevision(),
                cell.modRevision(),
                cell.modRevision(),
                cell.leaseId());
    }

    private NavigableMap<Bytes, Cell> rangeSlice(byte[] key, byte[] rangeEnd) {
        Bytes start = new Bytes(key);
        if (rangeEnd == null || rangeEnd.length == 0) {
            return data.subMap(start, true, start, true);
        }
        return data.subMap(start, true, new Bytes(rangeEnd), false);
    }

    private final class FakeKv implements KvClient {

        @Override
        public long getCommitTs() {
            failIfDown();
            return nextRevision();
        }

        @Override
        public PutResult put(
                byte[] key, byte[] value, long commitTs, boolean prevKv, byte[] requestId) {
            failIfDown();
            Bytes raw = new Bytes(key);
            long revision = nextRevision();
            Cell previous = data.put(raw, new Cell(value, revision, 0));
            return new PutResult(
                    prevKv && previous != null ? keyValue(raw, previous, false) : null, revision);
        }

        @Override
        public PutResult putWithLease(
                byte[] key,
                byte[] value,
                long leaseId,
                long commitTs,
                boolean prevKv,
                byte[] requestId) {
            failIfDown();
            if (!leases.containsKey(leaseId)) {
                throw new CoordException(ErrorCode.FAILED_PRECONDITION, "unknown lease " + leaseId);
            }
            Bytes raw = new Bytes(key);
            long revision = nextRevision();
            Cell previous = data.put(raw, new Cell(value, revision, leaseId));
            return new PutResult(
                    prevKv && previous != null ? keyValue(raw, previous, false) : null, revision);
        }

        @Override
        public RangeResult range(
                byte[] key,
                byte[] rangeEnd,
                long limit,
                long readTs,
                boolean keysOnly,
                boolean countOnly) {
            failIfDown();
            long snapshot = readTs == 0 ? revision : readTs;
            List<KeyValue> matched = new ArrayList<>();
            for (Map.Entry<Bytes, Cell> entry : rangeSlice(key, rangeEnd).entrySet()) {
                if (entry.getValue().modRevision() <= snapshot) {
                    matched.add(keyValue(entry.getKey(), entry.getValue(), keysOnly));
                }
            }
            int end = limit > 0 ? Math.min((int) limit, matched.size()) : matched.size();
            return new RangeResult(matched.subList(0, end), matched.size(), snapshot);
        }

        @Override
        public DeleteResult delete(
                byte[] key, byte[] rangeEnd, long commitTs, boolean prevKv, byte[] requestId) {
            failIfDown();
            List<KeyValue> removed = new ArrayList<>();
            List<Bytes> keys = new ArrayList<>();
            for (Map.Entry<Bytes, Cell> entry : rangeSlice(key, rangeEnd).entrySet()) {
                removed.add(keyValue(entry.getKey(), entry.getValue(), false));
                keys.add(entry.getKey());
            }
            data.keySet().removeAll(keys);
            return new DeleteResult(removed.size(), prevKv ? removed : List.of(), nextRevision());
        }
    }

    private final class FakeTxn implements TxnClient {

        @Override
        public Session begin(long startTs, int ttlMs) {
            failIfDown();
            return new FakeSession(startTs > 0 ? startTs : nextRevision());
        }

        @Override
        public Session begin() {
            return begin(0, 0);
        }

        private final class FakeSession implements Session {

            private final long snapshot;
            private final Map<Bytes, Long> readPoints = new HashMap<>();
            private final Map<Range, Long> readRanges = new HashMap<>();

            private FakeSession(long snapshot) {
                this.snapshot = snapshot;
            }

            @Override
            public byte[] txnId() {
                return new byte[0];
            }

            @Override
            public long startTs() {
                return snapshot;
            }

            @Override
            public ReadResult read(byte[] key, byte[] rangeEnd, long limit, boolean keysOnly) {
                failIfDown();
                Bytes start = new Bytes(key);
                List<KvClient.KeyValue> kvs = new ArrayList<>();
                if (rangeEnd == null || rangeEnd.length == 0) {
                    readPoints.put(start, modRevision(start));
                    Cell cell = data.get(start);
                    if (cell != null && cell.modRevision() <= snapshot) {
                        kvs.add(keyValue(start, cell, keysOnly));
                    }
                } else {
                    Range range = new Range(start, new Bytes(rangeEnd));
                    readRanges.put(range, fingerprint(range));
                    for (Map.Entry<Bytes, Cell> entry :
                            data.subMap(start, true, range.end(), false).entrySet()) {
                        if (entry.getValue().modRevision() > snapshot) {
                            continue;
                        }
                        if (limit > 0 && kvs.size() >= limit) {
                            break;
                        }
                        kvs.add(keyValue(entry.getKey(), entry.getValue(), keysOnly));
                    }
                }
                return new ReadResult(kvs, snapshot);
            }

            @Override
            public CommitResult commit(List<Mutation> mutations, byte[] requestId) {
                failIfDown();
                Objects.requireNonNull(mutations, "mutations");
                if (!valid()) {
                    return CommitResult.CONFLICT;
                }
                long revision = nextRevision();
                for (Mutation mutation : mutations) {
                    switch (mutation.op()) {
                        case PUT ->
                                data.put(
                                        new Bytes(mutation.key()),
                                        new Cell(mutation.value(), revision, mutation.leaseId()));
                        case DELETE -> data.remove(new Bytes(mutation.key()));
                        case DELETE_RANGE -> {
                            List<Bytes> keys = new ArrayList<>();
                            for (Bytes candidate :
                                    data.subMap(
                                                    new Bytes(mutation.key()),
                                                    true,
                                                    new Bytes(mutation.rangeEnd()),
                                                    false)
                                            .keySet()) {
                                keys.add(candidate);
                            }
                            data.keySet().removeAll(keys);
                        }
                    }
                }
                return new CommitResult(true, revision);
            }

            @Override
            public void abort() {
                readPoints.clear();
                readRanges.clear();
            }

            private boolean valid() {
                for (Map.Entry<Bytes, Long> entry : readPoints.entrySet()) {
                    if (!Objects.equals(entry.getValue(), modRevision(entry.getKey()))) {
                        return false;
                    }
                }
                for (Map.Entry<Range, Long> entry : readRanges.entrySet()) {
                    if (!Objects.equals(entry.getValue(), fingerprint(entry.getKey()))) {
                        return false;
                    }
                }
                return true;
            }

            private Long modRevision(Bytes key) {
                Cell cell = data.get(key);
                return cell == null ? null : cell.modRevision();
            }

            private Long fingerprint(Range range) {
                long count = 0;
                long sum = 0;
                for (Map.Entry<Bytes, Cell> entry :
                        data.subMap(range.start(), true, range.end(), false).entrySet()) {
                    count++;
                    sum += entry.getValue().modRevision();
                }
                return count * 1_000_003L + sum;
            }
        }
    }

    private final class FakeLease implements LeaseClient {

        @Override
        public Grant grant(long ttlSeconds, long leaseId, byte[] requestId) {
            failIfDown();
            long id = leaseId > 0 ? leaseId : ++leaseSeq;
            if (leases.putIfAbsent(id, ttlSeconds) != null) {
                throw new CoordException(ErrorCode.ALREADY_EXISTS, "lease exists: " + id);
            }
            return new Grant(id, ttlSeconds, revision);
        }

        @Override
        public long revoke(long leaseId, byte[] requestId) {
            failIfDown();
            return leases.remove(leaseId) == null ? 0 : nextRevision();
        }

        @Override
        public long keepAlive(long leaseId) {
            failIfDown();
            Long ttl = leases.get(leaseId);
            if (ttl == null) {
                throw new CoordException(ErrorCode.NOT_FOUND, "lease not found: " + leaseId);
            }
            return ttl;
        }

        @Override
        public State timeToLive(long leaseId, boolean keys) {
            failIfDown();
            Long ttl = leases.get(leaseId);
            if (ttl == null) {
                throw new CoordException(ErrorCode.NOT_FOUND, "lease not found: " + leaseId);
            }
            return new State(leaseId, ttl, ttl, List.of());
        }
    }
}
