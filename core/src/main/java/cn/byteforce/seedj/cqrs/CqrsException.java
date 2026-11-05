package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.InfrastructureException;

/**
 * CQRS 操作的统一异常。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CqrsException extends InfrastructureException {

    private final CqrsErrorCode errorCode;

    public CqrsException(CqrsErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public CqrsException(CqrsErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
        this.errorCode = errorCode;
    }

    public CqrsErrorCode getErrorCode() {
        return errorCode;
    }
}
