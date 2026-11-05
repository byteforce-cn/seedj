package cn.byteforce.seedj.core.clock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 时钟一致性测试套件：provider 实现方必须在 CI 中通过。
 */
public final class ClockTck {

    private ClockTck() {
    }

    public static void verifyBasic(Clock clock) {
        Instant t = clock.now();
        require(t != null, "now() must not return null");
        require(t.getNano() % 1000 == 0, "now() must be microsecond precision: " + t);
    }

    public static void verifyStrictlyIncreasing(Clock clock, int rounds) {
        Instant prev = clock.now();
        for (int i = 0; i < rounds; i++) {
            Instant cur = clock.now();
            require(cur.isAfter(prev), "now() must be strictly increasing");
            prev = cur;
        }
    }

    public static void verifyClockRollback(Clock clock, java.util.function.Consumer<Instant> rollback) {
        Instant first = clock.now();
        rollback.accept(first.minusSeconds(3600));
        Instant second = clock.now();
        require(second.isAfter(first), "must not go backwards after rollback");
    }

    public static void verifyConcurrent(Clock clock, int threads, int perThread) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Instant> results = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        results.add(clock.now());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();
        List<Instant> sorted = new ArrayList<>(results);
        sorted.sort(Instant::compareTo);
        for (int i = 1; i < sorted.size(); i++) {
            require(sorted.get(i).isAfter(sorted.get(i - 1)),
                    "concurrent now() produced duplicate/non-increasing value");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
