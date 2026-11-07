package cn.byteforce.seedj.infra.jdbc.lock;

import cn.byteforce.seedj.lock.LockErrorCode;
import cn.byteforce.seedj.lock.LockException;
import cn.byteforce.seedj.lock.LockLease;
import cn.byteforce.seedj.lock.LockProvider;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 基于 PostgreSQL 租约表的分布式锁。
 *
 * <p>用数据库 {@code now()} 作为租约时间，避免应用时钟漂移；获取用 {@code INSERT ... ON CONFLICT ... WHERE expires_at <
 * now()} 一条语句完成，天然并发安全。 不提供公平性与可重入；崩溃恢复依赖租约到期。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class PostgresLockProvider implements LockProvider {

    private static final int MAX_KEY_LENGTH = 255;

    private static final String ACQUIRE =
            """
            INSERT INTO seedj_lock_lease(lock_key, owner, expires_at)
            VALUES (?, ?, now() + (? * interval '1 millisecond'))
            ON CONFLICT (lock_key) DO UPDATE
              SET owner = EXCLUDED.owner, expires_at = EXCLUDED.expires_at
              WHERE seedj_lock_lease.expires_at < now()
            RETURNING owner
            """;

    private static final String RELEASE =
            """
            DELETE FROM seedj_lock_lease WHERE lock_key = ? AND owner = ?
            """;

    private final JdbcTemplate jdbc;

    /**
     * 构造 provider。
     *
     * @param jdbc JDBC 模板
     */
    public PostgresLockProvider(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<LockLease> tryAcquire(String key, Duration ttl) {
        validateKey(key);
        validateTtl(ttl);
        UUID owner = UUID.randomUUID();
        try {
            List<UUID> owners = jdbc.queryForList(ACQUIRE, UUID.class, key, owner, ttl.toMillis());
            if (owners.isEmpty() || !owner.equals(owners.get(0))) {
                return Optional.empty();
            }
            return Optional.of(LockLease.of(key, () -> release(key, owner)));
        } catch (DataAccessException error) {
            throw new LockException(
                    LockErrorCode.PROVIDER_UNAVAILABLE,
                    "postgres lock provider unavailable",
                    error);
        }
    }

    private void release(String key, UUID owner) {
        try {
            jdbc.update(RELEASE, key, owner);
        } catch (DataAccessException error) {
            throw new LockException(
                    LockErrorCode.PROVIDER_UNAVAILABLE, "postgres lock release failed", error);
        }
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
