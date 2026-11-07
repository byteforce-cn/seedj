package cn.byteforce.seedj.infra.local.numbering;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 编号 SPI 语义：并发不重复、scope 隔离、年度重置。 */
class NumberingTest {

    @Test
    @DisplayName("并发取号不重复")
    void concurrentNextIsUnique() throws Exception {
        InMemoryNumbering numbering = new InMemoryNumbering();
        Set<Long> values = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            var task = (java.util.concurrent.Callable<Void>) () -> {
                for (int i = 0; i < 100; i++) {
                    values.add(numbering.next("order"));
                }
                return null;
            };
            Future<?>[] futures = new Future<?>[8];
            for (int i = 0; i < futures.length; i++) {
                futures[i] = pool.submit(task);
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(values).hasSize(800);
        assertThat(values).doesNotContain(0L);
    }

    @Test
    @DisplayName("scope 与年度互相隔离")
    void scopesAndYearsAreIndependent() {
        InMemoryNumbering numbering = new InMemoryNumbering();

        assertThat(numbering.next("order", 2026)).isEqualTo(1);
        assertThat(numbering.next("order", 2026)).isEqualTo(2);
        assertThat(numbering.next("order", 2027)).isEqualTo(1);
        assertThat(numbering.next("voucher", 2026)).isEqualTo(1);
    }

    @Test
    @DisplayName("非法 scope/年度在入口失败")
    void invalidScopeAndYearAreRejected() {
        InMemoryNumbering numbering = new InMemoryNumbering();

        assertThatThrownBy(() -> numbering.next("Order"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> numbering.next("order", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> numbering.next("order", 10_000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
