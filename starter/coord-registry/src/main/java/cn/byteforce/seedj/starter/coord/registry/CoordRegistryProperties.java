package cn.byteforce.seedj.starter.coord.registry;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Coord 注册 / 发现配置属性。
 *
 * <p>前缀：{@code byteforce.coord.registry}。语义与默认值见
 * {@code starter/coord/ROADMAP.md} §0.2 约定 C1 / C6（Phase 0 定稿）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "byteforce.coord.registry")
public class CoordRegistryProperties {

    /**
     * 是否启用注册 / 发现自动配置。
     */
    private boolean enabled = true;

    /**
     * 是否启用启动期自动注册与关闭期优雅注销。
     *
     * <p>service_name / 端口均无法解析时跳过并 WARN（不阻塞启动）。</p>
     */
    private boolean autoRegister = true;

    /**
     * 注册的 service_name；缺省回退 {@code spring.application.name}。
     */
    private String serviceName;

    /**
     * 实例 ID；缺省 {@code {host}:{port}}（同键重复注册 = 覆盖续约，可安全复用）。
     */
    private String instanceId;

    /**
     * 注册视角主机名；缺省取本机 hostname（NAT / 容器场景显式覆盖）。
     */
    private String host;

    /**
     * 实例元数据中的协议标识（默认 {@code http}）。
     */
    private String protocol = "http";

    /**
     * 实例元数据中的权重（默认 100；v1 负载均衡不参与选择，预留）。
     */
    private int weight = 100;

    /**
     * 注册租约 TTL；心跳由 SDK {@code Registration} 维护。
     */
    private Duration ttl = Duration.ofSeconds(30);

    /**
     * 附加元数据（合并进 metadata JSON，同名键覆盖内置键）。
     */
    private final Map<String, String> metadata = new LinkedHashMap<>();

    /**
     * 发现消费（缓存降级）配置。
     */
    private final Discovery discovery = new Discovery();

    /**
     * TTL（秒，SDK 契约为 int）：下限 1 秒，上限 {@link Integer#MAX_VALUE}。
     *
     * @return 传给注册契约的 TTL 秒数
     */
    public int ttlSeconds() {
        return (int) Math.max(1L, Math.min(ttl.toSeconds(), Integer.MAX_VALUE));
    }

    /**
     * 获取是否启用。
     *
     * @return 是否启用注册 / 发现自动配置
     */
    public boolean isEnabled() { return enabled; }

    /**
     * 设置是否启用。
     *
     * @param enabled 是否启用注册 / 发现自动配置
     */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * 获取是否自动注册。
     *
     * @return 是否启用启动期自动注册
     */
    public boolean isAutoRegister() { return autoRegister; }

    /**
     * 设置是否自动注册。
     *
     * @param autoRegister 是否启用启动期自动注册
     */
    public void setAutoRegister(boolean autoRegister) { this.autoRegister = autoRegister; }

    /**
     * 获取服务名。
     *
     * @return 注册的 service_name
     */
    public String getServiceName() { return serviceName; }

    /**
     * 设置服务名。
     *
     * @param serviceName 注册的 service_name
     */
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    /**
     * 获取实例 ID。
     *
     * @return 实例 ID
     */
    public String getInstanceId() { return instanceId; }

    /**
     * 设置实例 ID。
     *
     * @param instanceId 实例 ID
     */
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    /**
     * 获取注册主机名。
     *
     * @return 注册视角主机名
     */
    public String getHost() { return host; }

    /**
     * 设置注册主机名。
     *
     * @param host 注册视角主机名
     */
    public void setHost(String host) { this.host = host; }

    /**
     * 获取协议标识。
     *
     * @return 协议标识
     */
    public String getProtocol() { return protocol; }

    /**
     * 设置协议标识。
     *
     * @param protocol 协议标识
     */
    public void setProtocol(String protocol) { this.protocol = protocol; }

    /**
     * 获取权重。
     *
     * @return 权重
     */
    public int getWeight() { return weight; }

    /**
     * 设置权重。
     *
     * @param weight 权重
     */
    public void setWeight(int weight) { this.weight = weight; }

    /**
     * 获取租约 TTL。
     *
     * @return 注册租约 TTL
     */
    public Duration getTtl() { return ttl; }

    /**
     * 设置租约 TTL。
     *
     * @param ttl 注册租约 TTL
     */
    public void setTtl(Duration ttl) { this.ttl = ttl; }

    /**
     * 获取附加元数据。
     *
     * @return 附加元数据
     */
    public Map<String, String> getMetadata() { return metadata; }

    /**
     * 获取发现配置。
     *
     * @return 发现消费配置
     */
    public Discovery getDiscovery() { return discovery; }

    /**
     * 发现消费配置（C6）：TTL 缓存 + Agent 不可达时的陈旧窗口降级。
     */
    public static class Discovery {

        /**
         * 是否启用发现结果缓存。
         */
        private boolean cacheEnabled = true;

        /**
         * 缓存 TTL：命中期内不触达 Agent。
         */
        private Duration cacheTtl = Duration.ofSeconds(30);

        /**
         * Agent 不可达时的陈旧窗口：超过缓存 TTL 但未超过该窗口时，降级返回陈旧结果（WARN）。
         */
        private Duration maxStale = Duration.ofMinutes(5);

        /**
         * 获取是否启用缓存。
         *
         * @return 是否启用发现缓存
         */
        public boolean isCacheEnabled() { return cacheEnabled; }

        /**
         * 设置是否启用缓存。
         *
         * @param cacheEnabled 是否启用发现缓存
         */
        public void setCacheEnabled(boolean cacheEnabled) { this.cacheEnabled = cacheEnabled; }

        /**
         * 获取缓存 TTL。
         *
         * @return 缓存 TTL
         */
        public Duration getCacheTtl() { return cacheTtl; }

        /**
         * 设置缓存 TTL。
         *
         * @param cacheTtl 缓存 TTL
         */
        public void setCacheTtl(Duration cacheTtl) { this.cacheTtl = cacheTtl; }

        /**
         * 获取陈旧窗口上限。
         *
         * @return 陈旧窗口上限
         */
        public Duration getMaxStale() { return maxStale; }

        /**
         * 设置陈旧窗口上限。
         *
         * @param maxStale 陈旧窗口上限
         */
        public void setMaxStale(Duration maxStale) { this.maxStale = maxStale; }
    }
}
