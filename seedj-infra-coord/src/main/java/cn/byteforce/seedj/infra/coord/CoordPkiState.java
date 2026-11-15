package cn.byteforce.seedj.infra.coord;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.seedj.pki.PkiProvider.RevocationReason;
import java.time.Instant;
import java.util.Optional;

/**
 * PKI 账本：alias → 当前身份、serial → 生命周期。
 *
 * <p>键是<b>带实例前缀的 CN</b>（{@code <prefix>:<alias>}），不是裸 alias：CN 全局唯一，裸 alias 会让同一
 * agent 上的不同部署共用一条记录。
 *
 * <p>coord 的 PKI 只有签发/轮换，没有吊销（无 CRL/OCSP），也没有「alias → 身份」的稳定映射。这两条
 * 是 {@link cn.byteforce.seedj.pki.PkiProvider} 的承诺，所以由本模块在 coord KV 上补：写走事务读集
 * 校验（{@link CoordKvStore#replaceIf} / {@link CoordKvStore#putIfAbsent}），多实例下吊销与轮换互不覆盖。
 *
 * <p>记录不是领域事件，字段随模块版本走（见 {@link CoordJson}）。
 */
final class CoordPkiState {

    private static final String ALIAS_PREFIX = "alias:";

    private static final String SERIAL_PREFIX = "serial:";

    private static final int RETRY_ATTEMPTS = 6;

    private static final int CAS_ATTEMPTS = 5;

    /** alias 的当前身份。{@code cn} 是加了实例前缀后的实际 CN。 */
    record AliasRecord(String alias, String cn, String currentSerial, long ttlSeconds) {}

    /** 一次签发的生命周期。{@code revoked} 只由 {@link #revoke} 写入，写入后不再翻转。 */
    record SerialRecord(
            String serial,
            String alias,
            String cn,
            long notBeforeMs,
            long notAfterMs,
            boolean revoked,
            String reason,
            long revokedAtMs) {}

    private final CoordKvStore store;

    private final CoordKeySpace space;

    CoordPkiState(CoordKvStore store, CoordKeySpace space) {
        this.store = store;
        this.space = space;
    }

    /**
     * 读某个身份（CN）的当前记录。
     *
     * <p>用 {@link CoordKvStore#readCurrent}：吊销判定决定「能不能签发」，不能建立在可能落后的
     * {@code safe_ts} 读上。
     */
    Optional<AliasRecord> readAlias(String alias) {
        return retry(
                        () ->
                                store.readCurrent(aliasKey(alias))
                                        .map(entry -> CoordJson.read(entry.value(), AliasRecord.class)))
                .value();
    }

    /** 记录 serial 的生命周期；同一 serial 重复写入幂等（首个为准）。 */
    void putSerialIfAbsent(SerialRecord record) {
        retry(() -> store.putIfAbsent(serialKey(record.serial()), CoordJson.write(record)));
    }

    /**
     * 读 serial 的生命周期。
     *
     * <p>同样走 {@link CoordKvStore#readCurrent}：吊销写在事务里，{@code safe_ts} 读可能看不到刚提交的
     * 记录，那样 {@code revoke} 之后 {@code status} 会返回 VALID。
     */
    Optional<SerialRecord> serial(String serial) {
        return retry(
                        () ->
                                store.readCurrent(serialKey(serial))
                                        .map(entry -> CoordJson.read(entry.value(), SerialRecord.class)))
                .value();
    }

    /**
     * CAS 更新身份的当前记录。
     *
     * @param expectedSerial 期望的旧 serial；{@code null} 表示要求记录尚不存在
     */
    boolean replaceAlias(String alias, String expectedSerial, AliasRecord next) {
        // CAS 冲突在并发/滞后时是常态，不是异常：重新读当前值再试。目标状态已达成也算成功。
        for (int attempt = 0; attempt < CAS_ATTEMPTS; attempt++) {
            Optional<AliasRecord> current = readAlias(alias);
            if (current.isPresent() && current.get().currentSerial().equals(next.currentSerial())) {
                return true;
            }
            if (expectedSerial != null
                    && (current.isEmpty()
                            || !current.get().currentSerial().equals(expectedSerial))) {
                return false;
            }
            byte[] compare =
                    retry(() -> store.readCurrent(aliasKey(alias)))
                            .value()
                            .map(CoordKvStore.Entry::value)
                            .orElse(null);
            if (compare == null) {
                if (expectedSerial != null) {
                    return false;
                }
                if (retry(() -> store.putIfAbsent(aliasKey(alias), CoordJson.write(next)))
                        .value()) {
                    return true;
                }
                continue;
            }
            if (retry(() -> store.replace(aliasKey(alias), compare, CoordJson.write(next)))
                    .value()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 吊销 serial。重复吊销幂等，且以首个原因为准：第二次调用不会再覆盖 reason。
     *
     * @return {@code true} 表示本次写入了吊销记录（首次），{@code false} 表示此前已吊销
     */
    boolean revoke(String serial, RevocationReason reason, Instant at) {
        String key = serialKey(serial);
        return retry(() -> store.replaceIf(
                key,
                entry -> !CoordJson.read(entry.value(), SerialRecord.class).revoked(),
                entry -> {
                    SerialRecord current = CoordJson.read(entry.value(), SerialRecord.class);
                    return CoordJson.write(
                            new SerialRecord(
                                    current.serial(),
                                    current.alias(),
                                    current.cn(),
                                    current.notBeforeMs(),
                                    current.notAfterMs(),
                                    true,
                                    reason.name(),
                                    at.toEpochMilli()));
                }))
                .value();
    }

    /** 写在账本里但没有签发记录（例如操作方吊销一个未知 serial）时用的占位记录。 */
    SerialRecord unknownSerial(String serial, RevocationReason reason, Instant at) {
        return new SerialRecord(serial, null, null, 0L, 0L, true, reason.name(), at.toEpochMilli());
    }

    boolean hasSerial(String serial) {
        return serial(serial).isPresent();
    }

    /**
     * 重试 agent 的时序失败。
     *
     * <p>coord 的 KV 读建在 {@code safe_ts} 上：刚提交的写要等 apply 追上才可读，期间 {@code TxnClient.begin()}
     * 直接抛 {@code read_ts exceeds safe_ts}（消息里就是 {@code retry later}）。账本的所有读写都是幂等的
     * （CAS / putIfAbsent / 带谓词的 replace），重试不会改变语义。
     */
    private <T> Retried<T> retry(java.util.function.Supplier<T> body) {
        CoordException last = null;
        for (int attempt = 0; attempt < RETRY_ATTEMPTS; attempt++) {
            try {
                return new Retried<>(body.get());
            } catch (CoordException e) {
                if (!transientFailure(e)) {
                    throw e;
                }
                last = e;
                sleep(attempt);
            }
        }
        throw last;
    }

    private static boolean transientFailure(CoordException e) {
        return switch (e.getErrorCode()) {
            case UNAVAILABLE,
                            DEADLINE_EXCEEDED,
                            NOT_LEADER,
                            SAFE_TS_LAG,
                            EPOCH_STALE,
                            TXN_CONFLICT,
                            COMMIT_REJECTED ->
                    true;
            case INTERNAL -> {
                String message = e.getMessage();
                yield message != null
                        && (message.contains("safe_ts") || message.contains("retry later"));
            }
            default -> false;
        };
    }

    private static void sleep(int attempt) {
        try {
            Thread.sleep(100L << attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 让 {@code void} 与有返回值的调用共用同一个重试循环。 */
    private record Retried<T>(T value) {}

    private String aliasKey(String alias) {
        return space.key(ALIAS_PREFIX + alias);
    }

    private String serialKey(String serial) {
        return space.key(SERIAL_PREFIX + serial);
    }
}
