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
     * 是否在缺少 {@code EisAuthorizer} Bean 时启用内建“全部放行”授权器。
     *
     * <p><b>安全语义</b>：默认为 {@code false}——授权缺省 fail-closed，应用未注册
     * {@code EisAuthorizer} 时启动失败。仅当显式置为 {@code true} 时才会使用内建放行
     * 授权器并在启动时打 {@code WARN}，<b>禁止用于生产</b>。</p>
     *
     * <p><b>装配口径</b>：是否注册内建全放行授权器由 {@code EisCoreAutoConfiguration}
     * 的 {@code @ConditionalOnProperty}（同一配置键 {@code byteforce.eis.core.allow-all-authorizer}）
     * 在装配期决定；本字段仅用于配置元数据（属性绑定与 IDE 提示），不参与运行时判定。</p>
     */
    private boolean allowAllAuthorizer = false;

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
     * 获取是否允许缺少授权器时全部放行。
     *
     * @return 允许全部放行为 {@code true}；缺省 {@code false}（fail-closed）
     */
    public boolean isAllowAllAuthorizer() {
        return allowAllAuthorizer;
    }

    /**
     * 设置是否允许缺少授权器时全部放行。
     *
     * @param allowAllAuthorizer 是否允许全部放行
     */
    public void setAllowAllAuthorizer(boolean allowAllAuthorizer) {
        this.allowAllAuthorizer = allowAllAuthorizer;
    }
}
