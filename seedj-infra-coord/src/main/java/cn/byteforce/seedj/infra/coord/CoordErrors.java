package cn.byteforce.seedj.infra.coord;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.seedj.eventbus.EventBusErrorCode;
import cn.byteforce.seedj.eventbus.EventBusException;
import cn.byteforce.seedj.id.IdErrorCode;
import cn.byteforce.seedj.id.IdException;
import cn.byteforce.seedj.lock.LockErrorCode;
import cn.byteforce.seedj.lock.LockException;

/** coord 错误码到 seedj 能力错误码的唯一映射点。 */
final class CoordErrors {

    private CoordErrors() {}

    static IdException id(CoordException e, CoordEndpoint endpoint) {
        return new IdException(
                IdErrorCode.STATE_UNAVAILABLE,
                "coord idgen failed at " + endpoint + ": " + e.getErrorCode(),
                e);
    }

    static LockException lock(CoordException e, CoordEndpoint endpoint) {
        LockErrorCode mapped =
                switch (e.getErrorCode()) {
                    case UNAVAILABLE,
                                    DEADLINE_EXCEEDED,
                                    AGENT_UNAVAILABLE,
                                    NOT_LEADER,
                                    SAFE_TS_LAG,
                                    EPOCH_STALE,
                                    TXN_NOT_FOUND,
                                    TXN_CONFLICT,
                                    COMMIT_REJECTED ->
                            LockErrorCode.PROVIDER_UNAVAILABLE;
                    default -> LockErrorCode.INTERNAL_ERROR;
                };
        return new LockException(
                mapped, "coord lock failed at " + endpoint + ": " + e.getErrorCode(), e);
    }

    static EventBusException consumer(CoordException e, CoordEndpoint endpoint) {
        EventBusErrorCode mapped =
                switch (e.getErrorCode()) {
                    case UNAVAILABLE,
                                    DEADLINE_EXCEEDED,
                                    AGENT_UNAVAILABLE,
                                    NOT_LEADER,
                                    SAFE_TS_LAG,
                                    EPOCH_STALE,
                                    TOPIC_EPOCH_STALE,
                                    GROUP_EPOCH_STALE,
                                    REBALANCE_IN_PROGRESS,
                                    TXN_NOT_FOUND,
                                    TXN_CONFLICT,
                                    COMMIT_REJECTED ->
                            EventBusErrorCode.TRANSPORT_UNAVAILABLE;
                    default -> EventBusErrorCode.CONSUMER_FAILED;
                };
        return new EventBusException(
                mapped, "coord consume failed at " + endpoint + ": " + e.getErrorCode(), e);
    }

    static EventBusException eventBus(
            CoordException e, EventBusErrorCode fallback, CoordEndpoint endpoint) {
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
        return new EventBusException(
                mapped, "coord eventbus failed at " + endpoint + ": " + e.getErrorCode(), e);
    }
}
