package cn.byteforce.seedj.id;

import cn.byteforce.seedj.core.InfrastructureException;

/**
 * 标识生成异常。
 */
public class IdException extends InfrastructureException {

    private final IdErrorCode errorCode;

    public IdException(IdErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public IdException(IdErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
        this.errorCode = errorCode;
    }

    public IdErrorCode getErrorCode() {
        return errorCode;
    }
}
