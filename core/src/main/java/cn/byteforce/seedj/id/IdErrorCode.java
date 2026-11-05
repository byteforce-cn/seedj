package cn.byteforce.seedj.id;

import cn.byteforce.seedj.core.ErrorCategory;
import cn.byteforce.seedj.core.ErrorDescriptor;

/**
 * 标识生成错误码。
 */
public enum IdErrorCode implements ErrorDescriptor {

    /** 本机时钟回拨超过允许阈值，继续生成会破坏单调性。 */
    CLOCK_BACKWARDS(ErrorCategory.BACKEND_ERROR, false),

    /** 节点编号池（1024）耗尽。 */
    NODE_ID_EXHAUSTED(ErrorCategory.CONFIG_ERROR, false),

    /** 节点编号非法（越界）。 */
    INVALID_NODE_ID(ErrorCategory.CONFIG_ERROR, false),

    /** 生成器已关闭。 */
    PROVIDER_CLOSED(ErrorCategory.CONFIG_ERROR, false),

    /** 状态文件不可读写。 */
    STATE_UNAVAILABLE(ErrorCategory.BACKEND_ERROR, true);

    private final ErrorCategory category;
    private final boolean defaultRetryable;

    IdErrorCode(ErrorCategory category, boolean defaultRetryable) {
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
