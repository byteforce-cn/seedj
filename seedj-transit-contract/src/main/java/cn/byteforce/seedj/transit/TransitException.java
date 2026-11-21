package cn.byteforce.seedj.transit;

import cn.byteforce.seedj.core.InfrastructureException;

/**
 * Transit 操作的异常。
 *
 * <p>调用方应基于 {@link #getErrorCode()} / {@code category()} / {@code isRetryable()} 分支，而不是解析 message。
 */
public class TransitException extends InfrastructureException {

    private final TransitErrorCode errorCode;

    public TransitException(TransitErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
        this.errorCode = errorCode;
    }

    public TransitException(
            TransitErrorCode errorCode, String message, boolean retryable, Throwable cause) {
        super(errorCode, message, cause, retryable);
        this.errorCode = errorCode;
    }

    public TransitErrorCode getErrorCode() {
        return errorCode;
    }
}
