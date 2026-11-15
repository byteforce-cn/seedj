package cn.byteforce.seedj.infra.coord;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.seedj.eventbus.EventBusErrorCode;
import cn.byteforce.seedj.eventbus.EventBusException;
import cn.byteforce.seedj.id.IdErrorCode;
import cn.byteforce.seedj.id.IdException;

/** coord 错误码到 seedj 能力错误码的唯一映射点。 */
final class CoordErrors {

    private CoordErrors() {}

    static IdException id(CoordException e) {
        return new IdException(
                IdErrorCode.STATE_UNAVAILABLE, "coord idgen failed: " + e.getErrorCode(), e);
    }

    static EventBusException eventBus(CoordException e, EventBusErrorCode fallback) {
        EventBusErrorCode mapped =
                switch (e.getErrorCode()) {
                    case UNAVAILABLE,
                                    DEADLINE_EXCEEDED,
                                    AGENT_UNAVAILABLE,
                                    NOT_LEADER,
                                    SAFE_TS_LAG,
                                    EPOCH_STALE ->
                            EventBusErrorCode.TRANSPORT_UNAVAILABLE;
                    case MESSAGE_TOO_LARGE, QUOTA_EXCEEDED, RESOURCE_EXHAUSTED, INVALID_ARGUMENT ->
                            EventBusErrorCode.SEND_FAILED;
                    default -> fallback;
                };
        return new EventBusException(mapped, "coord eventbus failed: " + e.getErrorCode(), e);
    }
}
