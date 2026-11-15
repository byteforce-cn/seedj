package cn.byteforce.seedj.infra.coord;

import static cn.byteforce.seedj.infra.coord.CoordTestFixture.ENDPOINT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.byteforce.coord.sdk.idgen.IdGenClient;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CoordNumberingTest {

    @Test
    void mapsScopeAndYearToSeparateIdNames() {
        IdGenClient client = mock(IdGenClient.class);
        when(client.nextId("seedj:app:numbering:order")).thenReturn(7L);
        when(client.nextId("seedj:app:numbering:order_2026")).thenReturn(8L);
        CoordNumbering numbering = new CoordNumbering(ENDPOINT, client, "seedj:app:numbering");

        assertThat(numbering.next("order")).isEqualTo(7L);
        assertThat(numbering.next("order", 2026)).isEqualTo(8L);
        verify(client).nextId("seedj:app:numbering:order");
        verify(client).nextId("seedj:app:numbering:order_2026");
    }

    @Test
    void rejectsIllegalScope() {
        CoordNumbering numbering =
                new CoordNumbering(ENDPOINT, mock(IdGenClient.class), "seedj:app:numbering");

        assertThatThrownBy(() -> numbering.next("Order"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> numbering.next("order-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentNumberingIsUnique() throws Exception {
        CoordNumbering numbering =
                new CoordNumbering(ENDPOINT, new FakeIdGen(), "seedj:app:numbering");
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int t = 0; t < 8; t++) {
                pool.submit(
                        () -> {
                            for (int i = 0; i < 200; i++) {
                                seen.add(numbering.next("order"));
                            }
                        });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(seen).hasSize(1600);
    }
}
