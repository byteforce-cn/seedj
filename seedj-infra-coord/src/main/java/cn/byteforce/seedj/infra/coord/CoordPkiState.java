package cn.byteforce.seedj.infra.coord;

import cn.byteforce.seedj.pki.PkiProvider.RevocationReason;
import java.time.Instant;
import java.util.Optional;

/**
 * PKI 账本：alias → 当前身份、serial → 生命周期。
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
     * 读 alias 的当前身份。
     *
     * <p>用 {@link CoordKvStore#readCurrent}：吊销判定决定「能不能签发」，不能建立在可能落后的
     * {@code safe_ts} 读上。
     */
    Optional<AliasRecord> readAlias(String alias) {
        return store.readCurrent(aliasKey(alias)).map(entry -> CoordJson.read(entry.value(), AliasRecord.class));
    }

    /** 记录 serial 的生命周期；同一 serial 重复写入幂等（首个为准）。 */
    void putSerialIfAbsent(SerialRecord record) {
        store.putIfAbsent(serialKey(record.serial()), CoordJson.write(record));
    }

    Optional<SerialRecord> serial(String serial) {
        return store.get(serialKey(serial)).map(entry -> CoordJson.read(entry.value(), SerialRecord.class));
    }

    /**
     * CAS 更新 alias 的当前身份。
     *
     * @param expectedSerial 期望的旧 serial；{@code null} 表示要求 alias 尚不存在
     */
    boolean replaceAlias(String alias, String expectedSerial, AliasRecord next) {
        byte[] expected = null;
        if (expectedSerial != null) {
            Optional<AliasRecord> current = readAlias(alias);
            if (current.isEmpty() || !current.get().currentSerial().equals(expectedSerial)) {
                return false;
            }
            expected = store.readCurrent(aliasKey(alias)).map(CoordKvStore.Entry::value).orElse(null);
            if (expected == null) {
                return false;
            }
        }
        if (expected == null) {
            return store.putIfAbsent(aliasKey(alias), CoordJson.write(next));
        }
        return store.replace(aliasKey(alias), expected, CoordJson.write(next));
    }

    /**
     * 吊销 serial。重复吊销幂等，且以首个原因为准：第二次调用不会再覆盖 reason。
     *
     * @return {@code true} 表示本次写入了吊销记录（首次），{@code false} 表示此前已吊销
     */
    boolean revoke(String serial, RevocationReason reason, Instant at) {
        String key = serialKey(serial);
        return store.replaceIf(
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
                });
    }

    /** 写在账本里但没有签发记录（例如操作方吊销一个未知 serial）时用的占位记录。 */
    SerialRecord unknownSerial(String serial, RevocationReason reason, Instant at) {
        return new SerialRecord(serial, null, null, 0L, 0L, true, reason.name(), at.toEpochMilli());
    }

    boolean hasSerial(String serial) {
        return store.get(serialKey(serial)).isPresent();
    }

    private String aliasKey(String alias) {
        return space.key(ALIAS_PREFIX + alias);
    }

    private String serialKey(String serial) {
        return space.key(SERIAL_PREFIX + serial);
    }
}
