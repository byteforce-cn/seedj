package cn.byteforce.seedj.infra.local.lock;

import cn.byteforce.seedj.lock.LockErrorCode;
import cn.byteforce.seedj.lock.LockException;
import cn.byteforce.seedj.lock.LockLease;
import cn.byteforce.seedj.lock.LockProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存锁：单进程、无协调。
 *
 * <p>只适用于本地开发、单元测试和单实例部署。<b>多实例部署绝对不能用</b>——它会让
 * 互斥在进程边界失效，而且不会有任何报错。因此 {@code InMemoryLockAutoConfiguration}
 * 默认关闭，必须显式 {@code seedj.lock.memory.enabled=true}。
 *
 * <p>过期判定基于注入的 {@link Clock}，测试可以用可控时钟验证租约到期。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class InMemoryLockProvider implements LockProvider {

    private record Held(String token, Instant expiresAt) {
    }

    private final Clock clock;

    private final ConcurrentMap<String, Held> held = new ConcurrentHashMap<>();

    private volatile boolean closed;

    public InMemoryLockProvider() {
        this(Clock.systemUTC());
    }

    public InMemoryLockProvider(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<LockLease> tryAcquire(String key, Duration ttl) {
        if (closed) {
            throw new LockException(LockErrorCode.PROVIDER_CLOSED, "lock provider is closed");
        }
        if (key == null || key.isBlank()) {
            throw new LockException(LockErrorCode.INVALID_KEY, "lock key must not be blank");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new LockException(LockErrorCode.INVALID_TTL, "ttl must be positive: " + ttl);
        }

        Instant now = clock.instant();
        String token = UUID.randomUUID().toString();
        boolean[] acquired = {false};

        held.compute(key, (ignored, current) -> {
            if (current != null && current.expiresAt().isAfter(now)) {
                return current;
            }
            acquired[0] = true;
            return new Held(token, now.plus(ttl));
        });

        if (!acquired[0]) {
            return Optional.empty();
        }
        return Optional.of(LockLease.of(key, () ->
                held.computeIfPresent(key, (ignored, current) ->
                        current.token().equals(token) ? null : current)));
    }

    @Override
    public void close() {
        closed = true;
        held.clear();
    }
}
