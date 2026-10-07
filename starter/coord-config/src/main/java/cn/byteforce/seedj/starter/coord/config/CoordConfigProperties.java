package cn.byteforce.seedj.starter.coord.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Coord 配置导入 / 刷新属性。
 *
 * <p>前缀：{@code byteforce.coord.config}。语义见 {@code starter/coord/ROADMAP.md}
 * §0.2 约定 C2（Phase 0 定稿）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "byteforce.coord.config")
public class CoordConfigProperties {

    /**
     * 是否启用配置模块自动配置（导入由 {@code spring.config.import} 独立触发）。
     */
    private boolean enabled = true;

    /**
     * 运行期刷新配置。
     */
    private final Refresh refresh = new Refresh();

    /**
     * 获取是否启用。
     *
     * @return 是否启用配置模块自动配置
     */
    public boolean isEnabled() { return enabled; }

    /**
     * 设置是否启用。
     *
     * @param enabled 是否启用配置模块自动配置
     */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * 获取刷新配置。
     *
     * @return 运行期刷新配置
     */
    public Refresh getRefresh() { return refresh; }

    /**
     * 运行期刷新配置（watch → 可变属性源 → rebind）。
     */
    public static class Refresh {

        /**
         * 是否启用运行期刷新（关闭后 import 仅提供启动期快照）。
         */
        private boolean enabled = true;

        /**
         * 获取是否启用刷新。
         *
         * @return 是否启用运行期刷新
         */
        public boolean isEnabled() { return enabled; }

        /**
         * 设置是否启用刷新。
         *
         * @param enabled 是否启用运行期刷新
         */
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
