package cn.byteforce.seedj.audit;

import cn.byteforce.seedj.core.ErrorCategory;
import cn.byteforce.seedj.core.ErrorDescriptor;

/**
 * 审计能力的错误码。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum AuditErrorCode implements ErrorDescriptor {

    /** 审计要求调用方已开启事务，但当前没有活动事务。 */
    TX_REQUIRED(ErrorCategory.CONFIG_ERROR, false),

    /** 审计已开启，但类路径上没有 {@code AuditStore} 实现。 */
    STORE_MISSING(ErrorCategory.CONFIG_ERROR, false),

    /** 存储暂时不可用（连接、锁等待等）。 */
    STORE_UNAVAILABLE(ErrorCategory.BACKEND_ERROR, true),

    /** 追加审计条目失败。 */
    APPEND_FAILED(ErrorCategory.BACKEND_ERROR, true);

    private final ErrorCategory category;
    private final boolean defaultRetryable;

    AuditErrorCode(ErrorCategory category, boolean defaultRetryable) {
        this.category = category;
        this.defaultRetryable = defaultRetryable;
    }

    @Override
    public ErrorCategory category() {
        return category;
    }

    @Override
    public boolean defaultRetryable() {
        return defaultRetryable;
    }
}
