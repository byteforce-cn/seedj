package cn.byteforce.seedj.starter.coord.lock;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Coord 分布式锁配置属性。
 *
 * <p>前缀：{@code byteforce.coord.lock}。语义见 {@code starter/coord/ROADMAP.md}
 * §0.2 约定 C3（Phase 0 定稿）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "byteforce.coord.lock")
public class CoordLockProperties {

    /**
     * 是否启用锁服务封装。
     */
    private boolean enabled = true;

    /**
     * 持有者 ID；缺省 {@code {spring.application.name ?: "app"}:{hostname}:{pid}}。
     */
    private String holderId;

    /**
     * 默认租约 TTL（看门狗续约周期 = ttl/3，下限 1s）。
     */
    private Duration ttl = Duration.ofSeconds(30);

    /**
     * 获取是否启用。
     *
     * @return 是否启用锁服务封装
     */
    public boolean isEnabled() { return enabled; }

    /**
     * 设置是否启用。
     *
     * @param enabled 是否启用锁服务封装
     */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * 获取持有者 ID。
     *
     * @return 持有者 ID
     */
    public String getHolderId() { return holderId; }

    /**
     * 设置持有者 ID。
     *
     * @param holderId 持有者 ID
     */
    public void setHolderId(String holderId) { this.holderId = holderId; }

    /**
     * 获取租约 TTL。
     *
     * @return 默认租约 TTL
     */
    public Duration getTtl() { return ttl; }

    /**
     * 设置租约 TTL。
     *
     * @param ttl 默认租约 TTL
     */
    public void setTtl(Duration ttl) { this.ttl = ttl; }
}
