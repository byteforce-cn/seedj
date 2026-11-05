package cn.byteforce.seedj.lock;

import cn.byteforce.seedj.core.InfrastructureException;

/**
 * 分布式锁操作的统一异常。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class LockException extends InfrastructureException {

    private final LockErrorCode errorCode;

    public LockException(LockErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public LockException(LockErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
        this.errorCode = errorCode;
    }

    public LockErrorCode getErrorCode() {
        return errorCode;
    }
}
