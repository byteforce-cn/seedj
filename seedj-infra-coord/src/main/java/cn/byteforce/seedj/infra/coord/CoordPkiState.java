package cn.byteforce.seedj.infra.coord;

import cn.byteforce.coord.sdk.CoordException;
import java.util.Optional;

/**
 * PKI alias 账本：{@code CN -> 当前 serial}。
 *
 * <p>键是<b>带实例前缀的 CN</b>（{@code <prefix>:<alias>}），不是裸 alias：CN 全局唯一，裸 alias 会让同一
 * agent 上的不同部署共用一条记录。
 *
 * <p>coord 0.4.4 起吊销/状态/CRL 都是 CA 侧的权威事实，这里只保留契约承诺的「alias → 身份」稳定映射。
 * 写走事务读集校验（{@link CoordKvStore#replaceIf} / {@link CoordKvStore#putIfAbsent}），多实例下移动
 * alias 不会互相覆盖。
 *
 * <p>记录不是领域事件，字段随模块版本走（见 {@link CoordJson}）。
 */
final class CoordPkiState {

    private static final String ALIAS_PREFIX = "alias:";

    private static final int RETRY_ATTEMPTS = 6;

    private static final int CAS_ATTEMPTS = 5;

    /** alias 的当前身份。{@code cn} 是加了实例前缀后的实际 CN。 */
    record AliasRecord(String alias, String cn, String currentSerial, long ttlSeconds) {}

    private final CoordKvStore store;

    private final CoordKeySpace space;

    CoordPkiState(CoordKvStore store, CoordKeySpace space) {
        this.store = store;
        this.space = space;
    }

    /**
     * 读某个身份（CN）的当前记录。
     *
     * <p>用 {@link CoordKvStore#readCurrent}：吊销判定决定「能不能签发」，不能建立在可能落后的 {@code safe_ts} 读上。
     */
    Optional<AliasRecord> readAlias(String alias) {
        return retry(
                        () ->
                                store.readCurrent(aliasKey(alias))
                                        .map(
                                                entry ->
                                                        CoordJson.read(
                                                                entry.value(), AliasRecord.class)))
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
            if (current.isPresent()
                    && current.get().currentSerial().equals(next.currentSerial())) {
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
     * 重试 agent 的时序失败。
     *
     * <p>coord 的 KV 读建在 {@code safe_ts} 上：刚提交的写要等 apply 追上才可读，期间 {@code TxnClient.begin()}
     * 直接抛 {@code read_ts exceeds safe_ts}（消息里就是 {@code retry later}）。账本的所有读写都是幂等的
     * （CAS / putIfAbsent），重试不会改变语义。
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
}
