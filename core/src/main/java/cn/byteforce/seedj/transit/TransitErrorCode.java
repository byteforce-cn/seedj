package cn.byteforce.seedj.transit;

import cn.byteforce.seedj.core.ErrorCategory;
import cn.byteforce.seedj.core.ErrorDescriptor;

/**
 * Transit 错误码。
 *
 * <p>调用方按 {@link ErrorCategory} 决定重试策略，不需要解析 message。
 */
public enum TransitErrorCode implements ErrorDescriptor {
    KEY_NOT_FOUND(ErrorCategory.CLIENT_ERROR, false),
    VERSION_NOT_FOUND(ErrorCategory.CLIENT_ERROR, false),
    TYPE_MISMATCH(ErrorCategory.CLIENT_ERROR, false),
    INVALID_CONTEXT(ErrorCategory.CLIENT_ERROR, false),
    INVALID_CIPHERTEXT(ErrorCategory.CLIENT_ERROR, false),
    DECRYPTION_FAILED(ErrorCategory.CLIENT_ERROR, false),
    KEY_ALREADY_EXISTS(ErrorCategory.CLIENT_ERROR, false),
    UNSUPPORTED_ALGORITHM(ErrorCategory.CLIENT_ERROR, false),
    
    UNSUPPORTED_OPERATION(ErrorCategory.CONFIG_ERROR, false),
    INVALID_CONFIGURATION(ErrorCategory.CONFIG_ERROR, false),
    DELETION_NOT_ALLOWED(ErrorCategory.CONFIG_ERROR, false),
    NOT_EXPORTABLE(ErrorCategory.CONFIG_ERROR, false),
    PROVIDER_CLOSED(ErrorCategory.CONFIG_ERROR, false),
    
    RETRYABLE_NETWORK_ERROR(ErrorCategory.BACKEND_ERROR, true),
    BACKEND_UNAVAILABLE(ErrorCategory.BACKEND_ERROR, true),
    
    BATCH_SIZE_EXCEEDED(ErrorCategory.LIMIT_EXCEEDED, false);

    private final ErrorCategory category;
    private final boolean defaultRetryable;

    TransitErrorCode(ErrorCategory category, boolean defaultRetryable) {
        this.category = category;
        this.defaultRetryable = defaultRetryable;
    }
    @Override public ErrorCategory category() { return category; }
    @Override public boolean defaultRetryable() { return defaultRetryable; }
}
