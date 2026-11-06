package cn.byteforce.seedj.starter.cqrs.wiring;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.cqrs.spi.CommandStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

/**
 * 回收租约过期的 PENDING 命令：启动时执行一次，之后按固定间隔执行。
 *
 * <p>删除而不是标记 EXPIRED：崩溃的命令视为从未发生，幂等键释放，命令查询返回 404。
 * 回收与执行中的命令存在竞争；lease 必须大于 P99 执行时间，若仍在执行的 PENDING 被删，
 * 事务内 {@code complete} 更新 0 行会回滚并返回 503。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class CommandReaper implements InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(CommandReaper.class);

    private final CommandStore commands;

    private final Clock clock;

    private final Counter expired;

    /**
     * 构造回收器。
     *
     * @param commands 命令存储
     * @param clock    取时入口
     * @param metrics  指标注册表；可为 {@code null}
     */
    public CommandReaper(CommandStore commands, Clock clock, MeterRegistry metrics) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.expired = metrics == null ? null
                : Counter.builder("seedj.command.pending.expired")
                        .description("expired pending command records removed")
                        .register(metrics);
    }

    @Override
    public void afterPropertiesSet() {
        reap();
    }

    /**
     * 删除租约已过期的 PENDING 记录。
     */
    @Scheduled(fixedDelayString = "${seedj.command.reaper-interval:30s}")
    public void reap() {
        long removed = commands.expirePending(clock.now());
        if (removed > 0) {
            if (expired != null) {
                expired.increment(removed);
            }
            LOG.warn("seedj: reaped {} expired pending command record(s)", removed);
        }
    }
}
