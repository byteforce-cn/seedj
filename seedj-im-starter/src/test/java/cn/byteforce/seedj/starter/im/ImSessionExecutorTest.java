package cn.byteforce.seedj.starter.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 会话执行器：同 owner 串行、队列满即拒绝、关闭后不再执行。 */
class ImSessionExecutorTest {

    @Test
    @DisplayName("同一 owner 的任务串行执行")
    void sameOwnerRunsSequentially() {
        try (ImSessionExecutor executor = new ImSessionExecutor(2, 16)) {
            List<Long> order = new CopyOnWriteArrayList<>();
            CountDownLatch done = new CountDownLatch(3);
            for (int i = 0; i < 3; i++) {
                executor.execute(
                        "u-1",
                        () -> {
                            order.add(Thread.currentThread().threadId());
                            done.countDown();
                        });
            }
            await(done);
            assertThat(order).hasSize(3);
            assertThat(order.stream().distinct()).hasSize(1);
        }
    }

    @Test
    @DisplayName("队列满时抛拒绝，供分发层回 429")
    void rejectionWhenQueueIsFull() {
        try (ImSessionExecutor executor = new ImSessionExecutor(1, 1)) {
            CountDownLatch blocker = new CountDownLatch(1);
            CountDownLatch started = new CountDownLatch(1);
            executor.execute(
                    "u-1",
                    () -> {
                        started.countDown();
                        await(blocker);
                    });
            await(started);
            executor.execute("u-1", () -> {});

            assertThatThrownBy(() -> executor.execute("u-1", () -> {}))
                    .isInstanceOf(RejectedExecutionException.class);
            blocker.countDown();
        }
    }

    @Test
    @DisplayName("泳道数按配置下限兜底")
    void laneCountFallsBackToDefault() {
        try (ImSessionExecutor executor = new ImSessionExecutor(0, 0)) {
            assertThat(executor.lanes()).isEqualTo(ImSessionExecutor.DEFAULT_THREADS);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", interrupted);
        }
    }
}
