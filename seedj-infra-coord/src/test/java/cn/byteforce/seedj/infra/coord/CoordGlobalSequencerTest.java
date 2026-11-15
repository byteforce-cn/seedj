package cn.byteforce.seedj.infra.coord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.byteforce.coord.sdk.idgen.IdGenClient;
import org.junit.jupiter.api.Test;

class CoordGlobalSequencerTest {

    @Test
    void delegatesToIdGen() {
        IdGenClient client = mock(IdGenClient.class);
        when(client.nextId("seedj:app:global-sequencer")).thenReturn(42L);
        CoordGlobalSequencer sequencer =
                new CoordGlobalSequencer(client, "seedj:app:global-sequencer");

        assertThat(sequencer.next()).isEqualTo(42L);
    }
}
