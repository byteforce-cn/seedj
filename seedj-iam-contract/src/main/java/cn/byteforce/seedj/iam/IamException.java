package cn.byteforce.seedj.iam;

import java.util.Objects;

/** Base exception for IAM contracts. */
public class IamException extends RuntimeException {

    private final IamErrorCode code;

    public IamException(IamErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public IamException(IamErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public IamErrorCode getCode() {
        return code;
    }
}
