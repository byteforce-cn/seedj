package cn.byteforce.seedj.starter.eis.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 事件脊柱配置属性。
 *
 * <p>前缀：{@code byteforce.eis.domain}</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "byteforce.eis.domain")
public class EisDomainProperties {

    /**
     * 是否启用事件脊柱自动配置（类型注册表 / 编解码 / PostgreSQL 事件日志）。
     */
    private boolean enabled = true;

    /**
     * 租户是否必填（缺省 {@code true}）。
     *
     * <p><b>安全语义</b>：为 {@code true} 时，解析不到租户的读写直接失败（fail-closed），
     * 避免跨租户串数据。仅单租户部署可显式置为 {@code false}。</p>
     */
    private boolean tenantRequired = true;

    /**
     * 获取是否启用。
     *
     * @return 是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置是否启用。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 获取租户是否必填。
     *
     * @return 租户是否必填
     */
    public boolean isTenantRequired() {
        return tenantRequired;
    }

    /**
     * 设置租户是否必填。
     *
     * @param tenantRequired 租户是否必填
     */
    public void setTenantRequired(boolean tenantRequired) {
        this.tenantRequired = tenantRequired;
    }
}
