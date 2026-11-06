package cn.byteforce.seedj.starter.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 审计配置。
 *
 * <p>{@code enabled=true} 时必须有可用的 {@code AuditStore} 与
 * {@code PlatformTransactionManager}，否则上下文启动失败——审计不允许静默跳过。</p>
 */
@ConfigurationProperties(prefix = "seedj.audit")
public class AuditProperties {

    /**
     * 是否启用审计（默认 true）。关闭后已标注 {@code @Audited} 的命令会在首次执行时 WARN。
     */
    private boolean enabled = true;

    /**
     * 存储实现：{@code postgres}（需 DataSource）或 {@code memory}（仅开发/测试）。
     * 留空时由类路径上的实现决定。
     */
    private String provider;

    private Anchor anchor = new Anchor();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Anchor getAnchor() {
        return anchor;
    }

    public void setAnchor(Anchor anchor) {
        this.anchor = anchor;
    }

    /**
     * 定时锚定配置（默认关闭）。
     */
    public static class Anchor {

        /**
         * 是否注册定时锚定任务，默认 false。
         */
        private boolean enabled;

        /**
         * 两轮锚定之间的固定延迟（毫秒），默认 1 小时。
         */
        private long intervalMillis = 3_600_000L;

        /**
         * 首次锚定前的延迟（毫秒），默认立即执行。
         */
        private long initialDelayMillis;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getIntervalMillis() {
            return intervalMillis;
        }

        public void setIntervalMillis(long intervalMillis) {
            this.intervalMillis = intervalMillis;
        }

        public long getInitialDelayMillis() {
            return initialDelayMillis;
        }

        public void setInitialDelayMillis(long initialDelayMillis) {
            this.initialDelayMillis = initialDelayMillis;
        }
    }
}
