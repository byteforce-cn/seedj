package cn.byteforce.seedj.starter.coord.ratelimiter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Coord 限流封装配置属性。
 *
 * <p>前缀：{@code byteforce.coord.ratelimiter}。注意：配额本身不在 starter 侧
 * 定义 / 管理（C4）——此处仅控制封装是否启用。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "byteforce.coord.ratelimiter")
public class CoordRateLimiterProperties {

    /**
     * 是否启用限流封装（API Bean / AOP）。
     */
    private boolean enabled = true;

    /**
     * 是否启用 {@link CoordRateLimit} 注解 AOP（依赖容器默认 AOP 代理设施，
     * 即 {@code spring.aop.auto} 未被关闭）。
     */
    private boolean aopEnabled = true;

    /**
     * 获取是否启用。
     *
     * @return 是否启用限流封装
     */
    public boolean isEnabled() { return enabled; }

    /**
     * 设置是否启用。
     *
     * @param enabled 是否启用限流封装
     */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * 获取是否启用注解 AOP。
     *
     * @return 是否启用注解 AOP
     */
    public boolean isAopEnabled() { return aopEnabled; }

    /**
     * 设置是否启用注解 AOP。
     *
     * @param aopEnabled 是否启用注解 AOP
     */
    public void setAopEnabled(boolean aopEnabled) { this.aopEnabled = aopEnabled; }
}
