package cn.byteforce.seedj.audit;

import cn.byteforce.seedj.core.InfrastructureException;

/**
 * 审计链操作的统一异常。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class AuditException extends InfrastructureException {

    private final AuditErrorCode errorCode;

    public AuditException(AuditErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public AuditException(AuditErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
        this.errorCode = errorCode;
    }

    public AuditErrorCode getErrorCode() {
        return errorCode;
    }
}
