package cn.byteforce.seedj.lock;

import cn.byteforce.seedj.core.ErrorCategory;
import cn.byteforce.seedj.core.ErrorDescriptor;

/**
 * 分布式锁错误码。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum LockErrorCode implements ErrorDescriptor {

    /** 锁键为空或空白。 */
    INVALID_KEY(ErrorCategory.CLIENT_ERROR, false),

    /** 租约为零或负数。 */
    INVALID_TTL(ErrorCategory.CLIENT_ERROR, false),

    /** 锁已被其他持有者占用；调用方决定等待或放弃。 */
    LOCK_BUSY(ErrorCategory.CLIENT_ERROR, true),

    /** 锁服务不可达（网络、连接池、选举中）。 */
    PROVIDER_UNAVAILABLE(ErrorCategory.BACKEND_ERROR, true),

    /** provider 已关闭。 */
    PROVIDER_CLOSED(ErrorCategory.CONFIG_ERROR, false),

    /** 其他内部错误。 */
    INTERNAL_ERROR(ErrorCategory.BACKEND_ERROR, false);

    private final ErrorCategory category;
    private final boolean defaultRetryable;

    LockErrorCode(ErrorCategory category, boolean defaultRetryable) {
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
