package cn.byteforce.seedj.id;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IdGenerator 契约测试套件。
 */
public abstract class IdGeneratorContractTest {

    protected IdGenerator<Long> generator;

    protected abstract IdGenerator<Long> newGenerator();

    @BeforeEach
    void create() {
        generator = newGenerator();
    }

    @AfterEach
    void close() {
        if (generator != null) {
            generator.close();
        }
    }

    @Test
    @DisplayName("雪花 ID 唯一且单调递增")
    void snowflakeIsUniqueAndMonotonic() {
        long previous = Long.MIN_VALUE;
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < 20_000; i++) {
            long id = generator.id();
            assertThat(id).isPositive().isGreaterThan(previous);
            previous = id;
            assertThat(seen.add(id)).isTrue();
        }
    }


    @Test
    @DisplayName("并发调用不产生重复 ID")
    void concurrentUniqueness() throws Exception {
        int threads = 4;
        int perThread = 5_000;
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        ids.add(generator.id());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(ids).hasSize(threads * perThread);
    }

    @Test
    @DisplayName("close 幂等；关闭后生成抛异常")
    void closeSemantics() {
        generator.close();
        generator.close();

        assertThatThrownBy(() -> generator.id()).isInstanceOf(IdException.class);
    }
}
