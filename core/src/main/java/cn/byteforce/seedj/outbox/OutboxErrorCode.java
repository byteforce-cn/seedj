package cn.byteforce.seedj.outbox;

import cn.byteforce.seedj.core.ErrorCategory;
import cn.byteforce.seedj.core.ErrorDescriptor;

/** Outbox 错误码。 */
public enum OutboxErrorCode implements ErrorDescriptor {

    /** 存在 outbox 时，append 必须在活动事务内调用。 */
    TX_REQUIRED(ErrorCategory.CLIENT_ERROR, false),

    /** 记录字段不合法（缺 messageId / contentVersion &lt; 1 等）。 */
    INVALID_RECORD(ErrorCategory.CLIENT_ERROR, false),

    /** 租约已被其他实例抢占，本次写入被拒绝。 */
    LEASE_LOST(ErrorCategory.CLIENT_ERROR, false),

    /** 记录不存在或当前状态不允许该运维操作。 */
    RECORD_NOT_FOUND(ErrorCategory.CLIENT_ERROR, false),

    /** 没有可用的 outbox 存储（装配缺失，不是瞬时故障）。 */
    STORE_UNAVAILABLE(ErrorCategory.CONFIG_ERROR, false),

    /** sink 无法投递（含 eventbus 没有 transport）。relay 按可重试处理。 */
    DELIVERY_FAILED(ErrorCategory.BACKEND_ERROR, true),

    /** provider 已关闭。 */
    PROVIDER_CLOSED(ErrorCategory.CONFIG_ERROR, false),

    /** 其他内部错误。 */
    INTERNAL_ERROR(ErrorCategory.BACKEND_ERROR, false);

    private final ErrorCategory category;
    private final boolean defaultRetryable;

    OutboxErrorCode(ErrorCategory category, boolean defaultRetryable) {
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
