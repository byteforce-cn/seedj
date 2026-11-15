package cn.byteforce.seedj.infra.coord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;
import cn.byteforce.coord.sdk.idgen.IdGenClient;
import cn.byteforce.seedj.id.IdErrorCode;
import cn.byteforce.seedj.id.IdException;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoordIdGeneratorTest {

    @Test
    void fetchesBatchesAndReturnsStringIds() {
        IdGenClient client = mock(IdGenClient.class);
        when(client.nextBatch("app", 3)).thenReturn(List.of(10L, 11L, 12L), List.of(20L));

        CoordIdGenerator generator = new CoordIdGenerator(client, "app", 3);

        assertThat(generator.id()).isEqualTo("10");
        assertThat(generator.id()).isEqualTo("11");
        assertThat(generator.id()).isEqualTo("12");
        assertThat(generator.id()).isEqualTo("20");
        verify(client, times(2)).nextBatch("app", 3);
    }

    @Test
    void closeIsIdempotentAndRejectsFurtherIds() {
        IdGenClient client = mock(IdGenClient.class);
        when(client.nextBatch("app", 2)).thenReturn(List.of(1L, 2L));
        CoordIdGenerator generator = new CoordIdGenerator(client, "app", 2);
        assertThat(generator.id()).isEqualTo("1");

        generator.close();
        generator.close();

        assertThatThrownBy(generator::id)
                .isInstanceOf(IdException.class)
                .extracting(e -> ((IdException) e).getErrorCode())
                .isEqualTo(IdErrorCode.PROVIDER_CLOSED);
    }

    @Test
    void emptyBatchFailsClosed() {
        IdGenClient client = mock(IdGenClient.class);
        when(client.nextBatch("app", 2)).thenReturn(List.of());
        CoordIdGenerator generator = new CoordIdGenerator(client, "app", 2);

        assertThatThrownBy(generator::id)
                .isInstanceOf(IdException.class)
                .extracting(e -> ((IdException) e).getErrorCode())
                .isEqualTo(IdErrorCode.STATE_UNAVAILABLE);
    }

    @Test
    void coordFailureMapsToStateUnavailable() {
        IdGenClient client = mock(IdGenClient.class);
        when(client.nextBatch("app", 2))
                .thenThrow(new CoordException(ErrorCode.AGENT_UNAVAILABLE, "down"));
        CoordIdGenerator generator = new CoordIdGenerator(client, "app", 2);

        assertThatThrownBy(generator::id)
                .isInstanceOf(IdException.class)
                .extracting(e -> ((IdException) e).getErrorCode())
                .isEqualTo(IdErrorCode.STATE_UNAVAILABLE);
    }

    @Test
    void rejectsInvalidConfiguration() {
        IdGenClient client = mock(IdGenClient.class);
        assertThatThrownBy(() -> new CoordIdGenerator(client, " ", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoordIdGenerator(client, "app", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoordIdGenerator(client, "app", 10_001))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
