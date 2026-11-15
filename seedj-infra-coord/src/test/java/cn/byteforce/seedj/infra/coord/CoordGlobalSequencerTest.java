package cn.byteforce.seedj.infra.coord;

import static cn.byteforce.seedj.infra.coord.CoordTestFixture.ENDPOINT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.byteforce.coord.sdk.idgen.IdGenClient;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CoordGlobalSequencerTest {

    @Test
    void delegatesToIdGen() {
        IdGenClient client = mock(IdGenClient.class);
        when(client.nextId("seedj:app:global-sequencer")).thenReturn(42L);
        CoordGlobalSequencer sequencer =
                new CoordGlobalSequencer(ENDPOINT, client, "seedj:app:global-sequencer");

        assertThat(sequencer.next()).isEqualTo(42L);
    }

    @Test
    void concurrentNextIsUnique() throws Exception {
        CoordGlobalSequencer sequencer =
                new CoordGlobalSequencer(ENDPOINT, new FakeIdGen(), "seedj:app:global-sequencer");
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int t = 0; t < 8; t++) {
                pool.submit(
                        () -> {
                            for (int i = 0; i < 200; i++) {
                                seen.add(sequencer.next());
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
