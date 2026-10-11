package cn.byteforce.seedj.starter.eis.temporal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * EIS 时点查询配置属性。
 *
 * <p>前缀：{@code byteforce.eis.temporal}</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "byteforce.eis.temporal")
public class EisTemporalProperties {

    /**
     * 是否启用时点查询端点。
     */
    private boolean enabled = true;

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
}
