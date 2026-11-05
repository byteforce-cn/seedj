package cn.byteforce.seedj.audit.wiring;

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
}
