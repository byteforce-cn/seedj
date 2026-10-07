package cn.byteforce.seedj.starter.coord.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Coord 可观测性桥配置属性。
 *
 * <p>前缀：{@code byteforce.coord.observability}。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "byteforce.coord.observability")
public class CoordObservabilityProperties {

    /**
     * 是否启用 Micrometer 指标桥。
     */
    private boolean enabled = true;

    /**
     * 获取是否启用。
     *
     * @return 是否启用 Micrometer 指标桥
     */
    public boolean isEnabled() { return enabled; }

    /**
     * 设置是否启用。
     *
     * @param enabled 是否启用 Micrometer 指标桥
     */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
