package cn.byteforce.seedj.infra.coord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.byteforce.coord.sdk.idgen.IdGenClient;
import org.junit.jupiter.api.Test;

class CoordNumberingTest {

    @Test
    void mapsScopeAndYearToSeparateIdNames() {
        IdGenClient client = mock(IdGenClient.class);
        when(client.nextId("seedj:app:numbering:order")).thenReturn(7L);
        when(client.nextId("seedj:app:numbering:order_2026")).thenReturn(8L);
        CoordNumbering numbering = new CoordNumbering(client, "seedj:app:numbering");

        assertThat(numbering.next("order")).isEqualTo(7L);
        assertThat(numbering.next("order", 2026)).isEqualTo(8L);
        verify(client).nextId("seedj:app:numbering:order");
        verify(client).nextId("seedj:app:numbering:order_2026");
    }

    @Test
    void rejectsIllegalScope() {
        CoordNumbering numbering =
                new CoordNumbering(mock(IdGenClient.class), "seedj:app:numbering");

        assertThatThrownBy(() -> numbering.next("Order"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> numbering.next("order-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
