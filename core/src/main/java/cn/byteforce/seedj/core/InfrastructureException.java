package cn.byteforce.seedj.core;

import java.util.Objects;

/**
 * 基础设施层的统一异常基类。
 *
 * <p>业务方只需要：
 * <pre>{@code
 * catch (InfrastructureException e) {
 *     if (e.category() == ErrorCategory.BACKEND_ERROR && e.isRetryable()) { ... }
 * }
 * }</pre>
 *
 * <p>各能力（pki / transit / eventbus）的异常都继承本类，并额外暴露自己强类型的
 * {@code getErrorCode()}。契约实现方禁止在契约边界抛 {@link IllegalStateException}
 * 之类的通用运行时异常。
 */
public class InfrastructureException extends RuntimeException {

    private final ErrorDescriptor error;
    private final boolean retryable;

    /**
     * 使用错误码的默认可重试性，不携带 cause。
     */
    public InfrastructureException(ErrorDescriptor error, String message) {
        this(error, message, null);
    }

    /**
     * 使用错误码的默认可重试性。
     */
    public InfrastructureException(ErrorDescriptor error, String message, Throwable cause) {
        this(error, message, cause, null);
    }

    /**
     * @param retryableOverride {@code null} 表示沿用错误码默认值；
     *                          非 null 时与默认值取交集（错误码本身不可重试则始终不可重试）
     */
    public InfrastructureException(ErrorDescriptor error, String message, Throwable cause,
                                   Boolean retryableOverride) {
        super(message, cause);
        this.error = Objects.requireNonNull(error, "error");
        this.retryable = retryableOverride == null
                ? error.defaultRetryable()
                : error.defaultRetryable() && retryableOverride;
    }

    /** 错误项（通常是能力自己的错误码枚举）。 */
    public ErrorDescriptor error() {
        return error;
    }

    /** 稳定错误码名称。 */
    public String code() {
        return error.name();
    }

    /** 错误分类。 */
    public ErrorCategory category() {
        return error.category();
    }

    /** 是否建议按退避策略重试。 */
    public boolean isRetryable() {
        return retryable;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{code=" + code()
                + ", category=" + category()
                + ", retryable=" + retryable
                + ", message='" + getMessage() + "'}";
    }
}
