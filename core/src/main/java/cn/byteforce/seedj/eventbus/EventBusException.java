package cn.byteforce.seedj.eventbus;

import cn.byteforce.seedj.core.InfrastructureException;

/** EventBus 操作的统一异常（transport 与消费侧）。 */
public class EventBusException extends InfrastructureException {

    private final EventBusErrorCode errorCode;

    public EventBusException(EventBusErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public EventBusException(EventBusErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
        this.errorCode = errorCode;
    }

    public EventBusErrorCode getErrorCode() {
        return errorCode;
    }
}
