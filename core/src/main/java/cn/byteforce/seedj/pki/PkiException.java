package cn.byteforce.seedj.pki;

import cn.byteforce.seedj.core.InfrastructureException;

/**
 * PKI 操作的异常。继承 {@link InfrastructureException}，因此业务方可以统一按 {@code category()} / {@code
 * isRetryable()} 处理。
 */
public class PkiException extends InfrastructureException {

    private final PkiErrorCode errorCode;

    public PkiException(PkiErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public PkiException(PkiErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
        this.errorCode = errorCode;
    }

    /** 保留的便捷构造：不指定错误码时按内部错误处理。 */
    public PkiException(String message, Throwable cause) {
        this(PkiErrorCode.INTERNAL_ERROR, message, cause);
    }

    /** 保留的便捷构造：不指定错误码时按内部错误处理。 */
    public PkiException(String message) {
        this(PkiErrorCode.INTERNAL_ERROR, message);
    }

    public PkiErrorCode getErrorCode() {
        return errorCode;
    }
}
