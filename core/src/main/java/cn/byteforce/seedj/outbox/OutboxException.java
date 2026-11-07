package cn.byteforce.seedj.outbox;

import cn.byteforce.seedj.core.InfrastructureException;

/** Outbox 操作的统一异常。 */
public class OutboxException extends InfrastructureException {

    private final OutboxErrorCode errorCode;

    public OutboxException(OutboxErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public OutboxException(OutboxErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
        this.errorCode = errorCode;
    }

    public OutboxErrorCode getErrorCode() {
        return errorCode;
    }
}
