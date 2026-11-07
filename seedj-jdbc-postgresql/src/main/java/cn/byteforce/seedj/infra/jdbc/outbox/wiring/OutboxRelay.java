package cn.byteforce.seedj.infra.jdbc.outbox.wiring;

import cn.byteforce.seedj.core.InfrastructureException;
import cn.byteforce.seedj.outbox.OutboxException;
import cn.byteforce.seedj.outbox.spi.ClaimRequest;
import cn.byteforce.seedj.outbox.spi.ClaimToken;
import cn.byteforce.seedj.outbox.spi.ClaimedRecord;
import cn.byteforce.seedj.outbox.spi.OutboxFailure;
import cn.byteforce.seedj.outbox.spi.OutboxSink;
import cn.byteforce.seedj.outbox.spi.OutboxStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Outbox 投递器。
 *
 * <p>自己持有调度线程：业务应用<b>不需要</b>加 {@code @EnableScheduling}， 也不依赖 {@code @Scheduled} 的代理机制。
 *
 * <p>没有 store 或没有 sink 时只打一条 info 日志并空转，绝不阻断启动。
 */
public class OutboxRelay implements InitializingBean, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final ObjectProvider<OutboxStore> outboxStore;
    private final ObjectProvider<OutboxSink> sink;
    private final OutboxProperties.Relay properties;
    private final OutboxMetrics metrics;

    private final Semaphore inFlight = new Semaphore(1);
    private final ReentrantLock relayLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();

    private ScheduledExecutorService scheduler;
    private ExecutorService completionExecutor;
    private ScheduledExecutorService heartbeatScheduler;

    private volatile OutboxStore store;
    private volatile OutboxSink outboxSink;

    public OutboxRelay(
            ObjectProvider<OutboxStore> outboxStore,
            ObjectProvider<OutboxSink> sink,
            OutboxProperties.Relay properties,
            OutboxMetrics metrics) {
        this.outboxStore = outboxStore;
        this.sink = sink;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.isEnabled()) {
            log.info("seedj outbox relay disabled by configuration");
            return;
        }
        this.store = outboxStore.getIfAvailable();
        this.outboxSink = sink.getIfAvailable();
        if (store == null) {
            log.info("seedj outbox relay idle: no OutboxStore on the classpath");
            return;
        }
        if (outboxSink == null) {
            log.info("seedj outbox relay idle: no OutboxSink on the classpath");
            return;
        }

        int permits = Math.max(1, properties.getMaxConcurrency());
        this.inFlight.drainPermits();
        this.inFlight.release(permits);

        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemon("seedj-outbox-relay"));
        this.completionExecutor =
                Executors.newFixedThreadPool(
                        Math.min(4, permits), daemon("seedj-outbox-completion"));
        this.heartbeatScheduler =
                Executors.newScheduledThreadPool(
                        Math.min(2, permits), daemon("seedj-outbox-heartbeat"));

        long interval = Math.max(50, properties.getPollInterval().toMillis());
        this.scheduler.scheduleWithFixedDelay(
                this::relay, interval, interval, TimeUnit.MILLISECONDS);
        log.info(
                "seedj outbox relay started: poll={}ms batch={} lease={}s concurrency={}",
                interval,
                properties.getBatchSize(),
                properties.getLease().toSeconds(),
                permits);
    }

    /** 执行一轮领取 + 投递。public 以便测试直接驱动，不必等调度。 */
    public void relay() {
        if (closed.get() || store == null || outboxSink == null) {
            return;
        }
        if (!relayLock.tryLock()) {
            return;
        }
        try {
            int available = inFlight.availablePermits();
            if (available <= 0) {
                return;
            }
            List<ClaimedRecord> records;
            try {
                records =
                        store.claim(
                                new ClaimRequest(
                                        Math.min(properties.getBatchSize(), available),
                                        properties.getLease()));
            } catch (RuntimeException e) {
                metrics.claimFailed();
                log.error("outbox claim failed", e);
                return;
            }
            if (records.isEmpty()) {
                return;
            }
            metrics.claimed(records.size());
            records.stream()
                    .map(record -> record.record().occurredAt())
                    .min(Instant::compareTo)
                    .ifPresent(
                            oldest -> metrics.oldestLag(Duration.between(oldest, Instant.now())));
            for (ClaimedRecord record : records) {
                if (!inFlight.tryAcquire()) {
                    break;
                }
                deliver(record);
            }
        } finally {
            relayLock.unlock();
        }
    }

    private void deliver(ClaimedRecord record) {
        Heartbeat heartbeat =
                store.supportsRenewal() ? startHeartbeat(record.token()) : Heartbeat.none();
        try {
            outboxSink
                    .deliver(record.record())
                    .whenCompleteAsync(
                            (ignored, error) -> {
                                try {
                                    if (error != null) {
                                        onFailure(record, heartbeat, error);
                                    } else {
                                        onSuccess(record, heartbeat);
                                    }
                                } finally {
                                    heartbeat.cancel();
                                    inFlight.release();
                                }
                            },
                            completionExecutor);
        } catch (RuntimeException e) {
            heartbeat.cancel();
            inFlight.release();
            onFailure(record, heartbeat, e);
        }
    }

    private void onSuccess(ClaimedRecord record, Heartbeat heartbeat) {
        if (heartbeat.isLost()) {
            metrics.leaseLost();
            log.warn("lease lost, skip markSent: {}", record.id());
            return;
        }
        try {
            store.markSent(record.token());
            metrics.sent();
        } catch (RuntimeException e) {
            log.error("markSent failed: {}", record.id(), e);
        }
    }

    private void onFailure(ClaimedRecord record, Heartbeat heartbeat, Throwable rawError) {
        Throwable error = unwrap(rawError);
        if (heartbeat.isLost()) {
            metrics.leaseLost();
            log.warn("lease lost, skip markFailed: {}", record.id());
            return;
        }
        boolean retryable = isRetryable(error);
        Duration delay = retryable ? backoff(record.attempts()) : Duration.ZERO;
        try {
            store.markFailed(
                    record.token(),
                    new OutboxFailure(error.getClass().getName(), error.getMessage(), retryable),
                    delay);
            metrics.failed(retryable);
        } catch (RuntimeException e) {
            log.error("markFailed failed: {}", record.id(), e);
        }
    }

    private Heartbeat startHeartbeat(ClaimToken token) {
        long interval = Math.max(1_000, token.leaseDuration().toMillis() / 3);
        AtomicBoolean lost = new AtomicBoolean(false);
        AtomicInteger consecutiveFailures = new AtomicInteger();
        ScheduledFuture<?>[] ref = new ScheduledFuture<?>[1];

        ref[0] =
                heartbeatScheduler.scheduleAtFixedRate(
                        () -> {
                            if (lost.get() || closed.get()) {
                                return;
                            }
                            try {
                                if (!store.renewLease(token)) {
                                    markLeaseLost(token, lost, ref);
                                    return;
                                }
                                consecutiveFailures.set(0);
                            } catch (RuntimeException e) {
                                if (consecutiveFailures.incrementAndGet()
                                        >= properties.getLeaseRenewFailures()) {
                                    markLeaseLost(token, lost, ref);
                                }
                            }
                        },
                        interval,
                        interval,
                        TimeUnit.MILLISECONDS);

        return new Heartbeat(ref[0], lost);
    }

    private void markLeaseLost(ClaimToken token, AtomicBoolean lost, ScheduledFuture<?>[] ref) {
        lost.set(true);
        if (ref[0] != null) {
            ref[0].cancel(false);
        }
        metrics.leaseLost();
        log.warn("lease lost: {}", token.recordId());
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
    }

    private static boolean isRetryable(Throwable error) {
        if (error instanceof InfrastructureException infrastructure) {
            return infrastructure.isRetryable();
        }
        return !(error instanceof IllegalArgumentException || error instanceof OutboxException);
    }

    private Duration backoff(int attempts) {
        Duration base = properties.getRetryBase();
        Duration max = properties.getRetryMax();
        if (attempts <= 0) {
            return base;
        }
        long multiplier = 1L << Math.min(attempts - 1, 20);
        long millis;
        try {
            millis = Math.multiplyExact(base.toMillis(), multiplier);
        } catch (ArithmeticException e) {
            return max;
        }
        return Duration.ofMillis(Math.min(millis, max.toMillis()));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }

        // 等正在投递的记录收尾（最多 5 秒），避免 markSent/markFailed 打在已关闭的线程池上。
        // relay 从未启动（没有 store / sink）时没有 in-flight 记录，直接跳过，
        // 否则空闲 relay 的 close() 会白等一个 5 秒窗口。
        if (scheduler != null) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            int permits = Math.max(1, properties.getMaxConcurrency());
            for (int i = 0; i < permits; i++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                try {
                    if (!inFlight.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (completionExecutor != null) {
            completionExecutor.shutdown();
            try {
                if (!completionExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    completionExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                completionExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("seedj outbox relay stopped");
    }

    private static java.util.concurrent.ThreadFactory daemon(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
