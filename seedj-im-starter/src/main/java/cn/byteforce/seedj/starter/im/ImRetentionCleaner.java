package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.im.spi.ImMessageStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 消息保留期清理：只删 {@code created_at < now - retention} 的消息，按批循环到不足一批。
 *
 * <p>没有配置 {@code seedj.im.message.retention} 时不启动调度，{@link #sweepOnce()} 是空操作。调度线程是
 * 本 bean 自带的 daemon 线程，宿主不需要 {@code @EnableScheduling}；单轮失败只记日志， 下一轮继续，避免一次数据库抖动让清理永久停摆。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class ImRetentionCleaner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ImRetentionCleaner.class);

    private static final int MAX_ROUNDS_PER_SWEEP = 100;

    private final ImMessageStore messages;

    private final ImProperties properties;

    private final Clock clock;

    private final ScheduledExecutorService scheduler;

    private final AtomicBoolean closed = new AtomicBoolean();

    ImRetentionCleaner(ImMessageStore messages, ImProperties properties, Clock clock) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        Duration retention = properties.message().retention();
        if (retention == null) {
            this.scheduler = null;
            return;
        }
        this.scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "seedj-im-retention");
                            thread.setDaemon(true);
                            return thread;
                        });
        long period = Math.max(1_000L, properties.message().retentionSweep().toMillis());
        scheduler.scheduleWithFixedDelay(this::sweepSafely, period, period, TimeUnit.MILLISECONDS);
    }

    /**
     * 执行一轮清理，返回删除条数。测试直接调用；生产由调度线程驱动。
     *
     * @return 本轮删除条数
     */
    int sweepOnce() {
        Duration retention = properties.message().retention();
        if (retention == null) {
            return 0;
        }
        Instant cutoff = clock.instant().minus(retention);
        int batch = properties.message().retentionBatch();
        int total = 0;
        for (int round = 0; round < MAX_ROUNDS_PER_SWEEP; round++) {
            int deleted = messages.deleteBefore(cutoff, batch);
            total += deleted;
            if (deleted < batch) {
                break;
            }
        }
        if (total > 0) {
            log.info("IM 保留期清理：删除 {} 条早于 {} 的消息", total, cutoff);
        }
        return total;
    }

    private void sweepSafely() {
        if (closed.get()) {
            return;
        }
        try {
            sweepOnce();
        } catch (RuntimeException failure) {
            log.warn("IM 保留期清理失败，下一轮重试: {}", failure.getMessage(), failure);
        }
    }

    @Override
    public void close() {
        closed.set(true);
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
