package cn.byteforce.seedj.starter.eis.authz;

import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzDecision;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzResolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内 TTL 缓存装饰器：加速远程 {@code /resolve} {@code /check} 调用。
 *
 * <p>语义边界（EIS 评估点 8）：<b>纯 best-effort 加速层、fail-open</b>——过期/缺失
 * 一律回源；读失败直接上抛（缓存绝不参与正确性）。失效由 TTL 兜底，另可经
 * {@link #invalidateAll()} 接受事件脉冲（域事件 → 清缓存）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class EisAuthzCachingOperations implements EisAuthzOperations {

    private static final Logger log = LoggerFactory.getLogger(EisAuthzCachingOperations.class);

    private static final int MAX_ENTRIES = 10_000;

    private final EisAuthzOperations delegate;

    private final Duration ttl;

    private final boolean enabled;

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    private final AtomicLong hits = new AtomicLong();

    private final AtomicLong misses = new AtomicLong();

    /**
     * 构造缓存装饰器。
     *
     * @param delegate 被装饰操作面
     * @param ttl      缓存时长（≤0 视为不缓存）
     * @param enabled  是否启用
     */
    public EisAuthzCachingOperations(EisAuthzOperations delegate, Duration ttl, boolean enabled) {
        this.delegate = delegate;
        this.ttl = ttl == null ? Duration.ZERO : ttl;
        this.enabled = enabled && !this.ttl.isZero() && !this.ttl.isNegative();
    }

    @Override
    public EisAuthzResolution resolve(String principalRef, Instant asOf, Instant recordedAsOf,
                                      JsonNode scopeFilter) {
        if (!enabled) {
            return delegate.resolve(principalRef, asOf, recordedAsOf, scopeFilter);
        }
        String key = "R|" + principalRef + "|" + asOf + "|" + recordedAsOf + "|"
                + (scopeFilter == null ? "" : scopeFilter.toString());
        return cached(key, EisAuthzResolution.class,
                () -> delegate.resolve(principalRef, asOf, recordedAsOf, scopeFilter));
    }

    @Override
    public EisAuthzDecision check(String principalRef, String permission, Instant asOf, Instant recordedAsOf) {
        if (!enabled) {
            return delegate.check(principalRef, permission, asOf, recordedAsOf);
        }
        String key = "C|" + principalRef + "|" + permission + "|" + asOf + "|" + recordedAsOf;
        return cached(key, EisAuthzDecision.class, () -> delegate.check(principalRef, permission, asOf,
                recordedAsOf));
    }

    /**
     * 清空全部缓存（事件失效脉冲入口；fail-open）。
     */
    public void invalidateAll() {
        cache.clear();
        log.debug("授权缓存已整体失效");
    }

    /**
     * 获取当前缓存条目数（诊断）。
     *
     * @return 条目数
     */
    public int cacheSize() {
        return cache.size();
    }

    /**
     * 获取命中/未命中计数（诊断）。
     *
     * @return [hits, misses]
     */
    public long[] stats() {
        return new long[] {hits.get(), misses.get()};
    }

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, Class<T> type, java.util.function.Supplier<T> loader) {
        Entry entry = cache.get(key);
        if (entry != null && !entry.expired()) {
            hits.incrementAndGet();
            return (T) entry.value;
        }
        misses.incrementAndGet();
        T value = loader.get();
        if (cache.size() >= MAX_ENTRIES) {
            // 简单上界：防无界膨胀（授权键空间有限，触顶属异常流量）
            cache.clear();
            log.warn("授权缓存触达上界 {}，已整体清空", MAX_ENTRIES);
        }
        cache.put(key, new Entry(value, Instant.now().plus(ttl)));
        return value;
    }

    /**
     * 缓存条目（值 + 过期时刻）。
     */
    private static final class Entry {

        private final Object value;

        private final Instant expiresAt;

        private Entry(Object value, Instant expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }

        private boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    /**
     * 诊断辅助：迭代键集（测试用）。
     *
     * @return 键迭代器
     */
    Iterator<String> keys() {
        return cache.keySet().iterator();
    }
}
