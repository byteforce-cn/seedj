package cn.byteforce.seedj.infra.coord;

import static cn.byteforce.seedj.infra.coord.CoordTestFixture.ENDPOINT;
import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;
import cn.byteforce.seedj.eventbus.EventBusErrorCode;
import cn.byteforce.seedj.eventbus.EventBusException;
import cn.byteforce.seedj.id.IdErrorCode;
import cn.byteforce.seedj.id.IdException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CoordErrorsTest {

    @ParameterizedTest
    @CsvSource({
        "UNAVAILABLE,TRANSPORT_UNAVAILABLE",
        "DEADLINE_EXCEEDED,TRANSPORT_UNAVAILABLE",
        "AGENT_UNAVAILABLE,TRANSPORT_UNAVAILABLE",
        "NOT_LEADER,TRANSPORT_UNAVAILABLE",
        "MESSAGE_TOO_LARGE,SEND_FAILED",
        "QUOTA_EXCEEDED,SEND_FAILED",
        "RESOURCE_EXHAUSTED,SEND_FAILED",
        "INVALID_ARGUMENT,SEND_FAILED",
        "INTERNAL,INTERNAL_ERROR"
    })
    void eventBusMapsByErrorCode(ErrorCode coord, EventBusErrorCode expected) {
        EventBusException e =
                CoordErrors.eventBus(
                        new CoordException(coord, "x"), EventBusErrorCode.INTERNAL_ERROR, ENDPOINT);
        assertThat(e.getErrorCode()).isEqualTo(expected);
        assertThat(e).hasCauseInstanceOf(CoordException.class);
    }

    @ParameterizedTest
    @CsvSource({"UNAVAILABLE", "INVALID_ARGUMENT", "INTERNAL", "UNAUTHENTICATED"})
    void idFailuresAreBackendUnavailable(ErrorCode coord) {
        IdException e = CoordErrors.id(new CoordException(coord, "x"), ENDPOINT);
        assertThat(e.getErrorCode()).isEqualTo(IdErrorCode.STATE_UNAVAILABLE);
        assertThat(e).hasCauseInstanceOf(CoordException.class);
    }
}
