package cn.byteforce.seedj.starter.coord.registry;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.registry.Registry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 发现消费客户端（native API，非 Spring Cloud {@code DiscoveryClient}）：
 * 在 SDK {@link Registry} 之上按约定 C6 提供 TTL 缓存与陈旧窗口降级。
 *
 * <p>语义边界（C6）：</p>
 * <ul>
 *   <li>实例来源 = {@code Registry.discover}（线性一致），**不含健康标记**；
 *       不做主动探活；</li>
 *   <li>缓存命中（TTL 内）直接返回，不触达 Agent；</li>
 *   <li>Agent 不可达时，若旧值仍在陈旧窗口（{@code max-stale}）内，降级返回
 *       陈旧结果并 WARN；超窗抛 {@link CoordException}（fail loud，不静默）。</li>
 * </ul>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class CoordDiscoveryClient {

    private static final Logger log = LoggerFactory.getLogger(CoordDiscoveryClient.class);

    private final Registry registry;
    private final CoordRegistryProperties.Discovery discovery;
    private final Clock clock;
    private final Map<String, CachedInstances> cache = new ConcurrentHashMap<>();

    /**
     * 生产构造：系统时钟。
     *
     * @param registry SDK 注册面客户端
     * @param discovery 缓存降级配置（C6）
     */
    public CoordDiscoveryClient(Registry registry, CoordRegistryProperties.Discovery discovery) {
        this(registry, discovery, Clock.systemUTC());
    }

    /**
     * 可注入时钟的构造（测试用）。
     *
     * @param registry  SDK 注册面客户端
     * @param discovery 缓存降级配置
     * @param clock     时钟
     */
    CoordDiscoveryClient(Registry registry, CoordRegistryProperties.Discovery discovery, Clock clock) {
        this.registry = registry;
        this.discovery = discovery;
        this.clock = clock;
    }

    /**
     * 列出服务实例。
     *
     * @param serviceName 服务名
     * @return 实例视图列表（可能为空；不再返回内部可变列表）
     * @throws CoordException Agent 不可达且超出陈旧窗口
     */
    public List<CoordServiceInstance> getInstances(String serviceName) {
        if (!discovery.isCacheEnabled()) {
            return fetchDirect(serviceName);
        }
        CachedInstances cached = cache.get(serviceName);
        long now = clock.millis();
        if (cached != null && now - cached.fetchedAtMillis() < discovery.getCacheTtl().toMillis()) {
            return cached.instances();
        }
        try {
            List<CoordServiceInstance> fresh = fetchDirect(serviceName);
            cache.put(serviceName, new CachedInstances(fresh, now));
            return fresh;
        } catch (CoordException e) {
            if (cached != null && now - cached.fetchedAtMillis() < discovery.getMaxStale().toMillis()) {
                log.warn("发现请求失败，降级返回 {}ms 前的陈旧实例（服务 {}，陈旧窗口 {}）：{}",
                        now - cached.fetchedAtMillis(), serviceName, discovery.getMaxStale(), e.getMessage());
                return cached.instances();
            }
            throw e;
        }
    }

    /**
     * 失效指定服务的缓存（下次读取强制回源）。
     *
     * @param serviceName 服务名
     */
    public void invalidate(String serviceName) {
        cache.remove(serviceName);
    }

    /**
     * 失效全部缓存。
     */
    public void invalidateAll() {
        cache.clear();
    }

    private List<CoordServiceInstance> fetchDirect(String serviceName) {
        return registry.discover(serviceName).stream().map(CoordServiceInstance::of).toList();
    }

    /** 不可变缓存条目。 */
    private static final class CachedInstances {

        private final List<CoordServiceInstance> instances;
        private final long fetchedAtMillis;

        private CachedInstances(List<CoordServiceInstance> instances, long fetchedAtMillis) {
            this.instances = instances;
            this.fetchedAtMillis = fetchedAtMillis;
        }

        private List<CoordServiceInstance> instances() {
            return instances;
        }

        private long fetchedAtMillis() {
            return fetchedAtMillis;
        }
    }
}
