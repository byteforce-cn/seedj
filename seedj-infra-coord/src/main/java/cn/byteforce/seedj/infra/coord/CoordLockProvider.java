package cn.byteforce.seedj.infra.coord;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.seedj.lock.LockErrorCode;
import cn.byteforce.seedj.lock.LockException;
import cn.byteforce.seedj.lock.LockLease;
import cn.byteforce.seedj.lock.LockProvider;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;

/**
 * 用 coord KV + OCC 事务组合出来的分布式锁。
 *
 * <p>不直接使用 {@code LockClient}：coord 的锁租约只到秒级，但 {@link LockProvider} 的契约要求亚秒级 TTL （150ms
 * 到期可重入）。这里把持有者、租约标识与 {@code expiresAtEpochMilli} 写进 KV 记录，读时按本地时钟判断 是否过期，再用事务的读集校验保证「只有一个持有者能写成功」。
 *
 * <p>与所有租约锁一样：过期不等于业务结束，临界区内仍须幂等或用存储侧唯一约束兜底；过期判定使用各实例本地时钟， 时钟漂移会缩短或延长互斥窗口。
 */
final class CoordLockProvider implements LockProvider {

    private static final int MAX_KEY_LENGTH = 255;

    private static final int ACQUIRE_ATTEMPTS = 3;

    /** KV 里的锁记录；{@code leaseId} 用于拒绝过期持有者的释放。 */
    record LockRecord(String holderId, String leaseId, long expiresAtEpochMilli) {}

    private final CoordKvStore store;
    private final CoordEndpoint endpoint;
    private final String holderId;
    private final CoordKeySpace keys;
    private final Clock clock;

    private volatile boolean closed;

    CoordLockProvider(
            CoordKvStore store,
            CoordEndpoint endpoint,
            CoordKeySpace keys,
            String holderId,
            Clock clock) {
        this.store = store;
        this.endpoint = endpoint;
        this.keys = keys;
        this.clock = clock;
        if (holderId == null || holderId.isBlank()) {
            throw new IllegalArgumentException("coord lock holder id must not be blank");
        }
        this.holderId = holderId;
    }

    @Override
    public Optional<LockLease> tryAcquire(String key, Duration ttl) {
        if (closed) {
            throw new LockException(
                    LockErrorCode.PROVIDER_CLOSED, "coord lock provider is closed at " + endpoint);
        }
        validateKey(key);
        validateTtl(ttl);
        String recordKey = keys.key(key);
        byte[] candidate = candidate(ttl);
        try {
            for (int attempt = 0; attempt < ACQUIRE_ATTEMPTS; attempt++) {
                if (store.putIfAbsent(recordKey, candidate)) {
                    return Optional.of(lease(key, recordKey, candidate));
                }
                Optional<CoordKvStore.Entry> current = store.get(recordKey);
                if (current.isEmpty()) {
                    continue;
                }
                if (held(current.get())) {
                    return Optional.empty();
                }
                if (store.replace(recordKey, current.get().value(), candidate)) {
                    return Optional.of(lease(key, recordKey, candidate));
                }
            }
            return Optional.empty();
        } catch (CoordException e) {
            throw CoordErrors.lock(e, endpoint);
        }
    }

    @Override
    public void close() {
        closed = true;
    }

    private LockLease lease(String key, String recordKey, byte[] candidate) {
        return LockLease.of(key, () -> release(recordKey, candidate));
    }

    /** 只删自己写下的那份记录：租约过期被他人接管后，旧持有者的释放是无操作。 */
    private void release(String recordKey, byte[] candidate) {
        try {
            store.removeIfMatch(recordKey, candidate);
        } catch (CoordException e) {
            throw CoordErrors.lock(e, endpoint);
        }
    }

    private boolean held(CoordKvStore.Entry entry) {
        LockRecord record = record(entry);
        return clock.millis() < record.expiresAtEpochMilli();
    }

    private LockRecord record(CoordKvStore.Entry entry) {
        try {
            return CoordJson.read(entry.value(), LockRecord.class);
        } catch (JacksonException e) {
            throw new LockException(
                    LockErrorCode.INTERNAL_ERROR, "coord lock record is corrupt at " + endpoint, e);
        }
    }

    private byte[] candidate(Duration ttl) {
        return CoordJson.write(
                new LockRecord(
                        holderId, UUID.randomUUID().toString(), clock.millis() + ttl.toMillis()));
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new LockException(LockErrorCode.INVALID_KEY, "lock key is required");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new LockException(
                    LockErrorCode.INVALID_KEY, "lock key is longer than " + MAX_KEY_LENGTH);
        }
    }

    private static void validateTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new LockException(LockErrorCode.INVALID_TTL, "lock ttl must be positive");
        }
        if (ttl.toMillis() < 1) {
            throw new LockException(
                    LockErrorCode.INVALID_TTL, "lock ttl must be at least 1 millisecond");
        }
    }
}
