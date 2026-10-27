package cn.byteforce.seedj.starter.eis.domain.memory;

import cn.byteforce.seedj.starter.eis.domain.AccountEvent;
import cn.byteforce.seedj.starter.eis.domain.DomainEvent;
import cn.byteforce.seedj.starter.eis.domain.EventStream;
import cn.byteforce.seedj.starter.eis.domain.OptimisticLockingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内存事件日志：CAS、尾部加载与并发语义。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class InMemoryEventLogStoreTest {

    private static final String STREAM = "account-1";

    @Test
    @DisplayName("不存在的流返回空流（版本 0）")
    void emptyStream() {
        EventStream stream = new InMemoryEventLogStore().loadStream("missing");

        assertThat(stream.isEmpty()).isTrue();
        assertThat(stream.currentVersion()).isZero();
        assertThat(stream.streamId()).isEqualTo("missing");
    }

    @Test
    @DisplayName("追加后可按全量与尾部两种方式读取")
    void appendThenLoad() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        store.append(STREAM, "Account", 0L, List.of(new AccountEvent.Opened("alice")));
        store.append(STREAM, "Account", 1L,
                List.of(new AccountEvent.Deposited(10L), new AccountEvent.Deposited(20L)));

        EventStream all = store.loadStream(STREAM);
        assertThat(all.currentVersion()).isEqualTo(3L);
        assertThat(all.events()).hasSize(3);

        EventStream tail = store.loadStream(STREAM, 1L);
        assertThat(tail.currentVersion()).isEqualTo(3L);
        assertThat(tail.events()).containsExactly(
                new AccountEvent.Deposited(10L), new AccountEvent.Deposited(20L));

        assertThat(store.loadStream(STREAM, 99L).events()).isEmpty();
    }

    @Test
    @DisplayName("版本不匹配：抛冲突异常并带上期望/实际版本")
    void versionConflict() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        store.append(STREAM, "Account", 0L, List.of(new AccountEvent.Opened("alice")));

        assertThatThrownBy(() -> store.append(STREAM, "Account", 0L,
                List.of(new AccountEvent.Deposited(1L))))
                .isInstanceOf(OptimisticLockingException.class)
                .hasMessageContaining("expected version: 0")
                .hasMessageContaining("actual version:   1");
    }

    @Test
    @DisplayName("空事件列表被拒绝")
    void rejectsEmptyEvents() {
        assertThatThrownBy(() -> new InMemoryEventLogStore().append(STREAM, "Account", 0L, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("events must not be empty");
    }

    @Test
    @DisplayName("流之间互不影响")
    void streamsAreIsolated() {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        store.append("a", "Account", 0L, List.of(new AccountEvent.Opened("alice")));
        store.append("b", "Account", 0L, List.of(new AccountEvent.Deposited(5L)));

        assertThat(store.loadStream("a").currentVersion()).isEqualTo(1L);
        assertThat(store.loadStream("b").currentVersion()).isEqualTo(1L);
        assertThat(store.loadStream("a").events()).hasSize(1);
    }

    @Test
    @DisplayName("并发追加同一流：只有一个成功")
    void concurrentAppendOnlyOneWins() throws Exception {
        InMemoryEventLogStore store = new InMemoryEventLogStore();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<DomainEvent> events = List.of(new AccountEvent.Opened("alice"));

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        store.append(STREAM, "Account", 0L, events);
                        success.incrementAndGet();
                    } catch (OptimisticLockingException e) {
                        conflicts.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(store.loadStream(STREAM).currentVersion()).isEqualTo(1L);
    }
}
