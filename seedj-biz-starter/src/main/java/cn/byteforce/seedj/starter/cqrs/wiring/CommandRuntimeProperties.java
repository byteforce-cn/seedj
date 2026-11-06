package cn.byteforce.seedj.starter.cqrs.wiring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 命令运行时装配属性。
 *
 * <p>缺事务/锁/outbox 默认启动失败；只有显式 {@code seedj.command.degraded-mode=true}
 * 才允许退回直通事务、禁用锁与丢弃 outbox，并打印 WARN。该开关只应出现在 local/test。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "seedj.command")
public class CommandRuntimeProperties {

    /** 是否允许降级装配；默认 false。 */
    private boolean degradedMode;

    /** 是否要求 outbox；默认 true。degraded-mode 下允许显式关闭。 */
    private boolean requireOutbox = true;

    /** PENDING 租约时长；默认 60 秒。 */
    private Duration pendingLease = Duration.ofSeconds(60);

    public boolean isDegradedMode() {
        return degradedMode;
    }

    public void setDegradedMode(boolean degradedMode) {
        this.degradedMode = degradedMode;
    }

    public boolean isRequireOutbox() {
        return requireOutbox;
    }

    public void setRequireOutbox(boolean requireOutbox) {
        this.requireOutbox = requireOutbox;
    }

    public Duration getPendingLease() {
        return pendingLease;
    }

    public void setPendingLease(Duration pendingLease) {
        this.pendingLease = pendingLease;
    }
}
