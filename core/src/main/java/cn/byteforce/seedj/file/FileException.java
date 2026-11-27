package cn.byteforce.seedj.file;

import cn.byteforce.seedj.core.InfrastructureException;

/**
 * 文件操作的统一异常。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class FileException extends InfrastructureException {

    private final FileErrorCode errorCode;

    public FileException(FileErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public FileException(FileErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
        this.errorCode = errorCode;
    }

    public FileErrorCode getErrorCode() {
        return errorCode;
    }
}
