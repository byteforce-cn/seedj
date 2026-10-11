package cn.byteforce.seedj.starter.eis.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * EIS 核心运行时配置属性。
 *
 * <p>前缀：{@code byteforce.eis.core}</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "byteforce.eis.core")
public class EisCoreProperties {

    /**
     * 是否启用 EIS 核心自动配置（命令运行时 / 对象存储 / 端点）。
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
