package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.ErrorCategory;
import cn.byteforce.seedj.core.ErrorDescriptor;

/**
 * CQRS 错误码。
 *
 * <p>命令侧（事件存储）与查询侧（投影）共用一套分类：调用方只需要判
 * {@link cn.byteforce.seedj.core.InfrastructureException#isRetryable()}，
 * 不需要认识具体适配器。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum CqrsErrorCode implements ErrorDescriptor {

    /** 流标识不合法（类型名为空白、标识为 null）。 */
    INVALID_STREAM_ID(ErrorCategory.CLIENT_ERROR, false),

    /** 标识无法编码/解码（codec 与标识类型不匹配、文本格式非法）。 */
    INVALID_ID(ErrorCategory.CLIENT_ERROR, false),

    /** 乐观并发冲突：流版本已变化，调用方应重新加载后重试。 */
    CONCURRENCY_CONFLICT(ErrorCategory.CLIENT_ERROR, true),

    /** 命令被聚合拒绝（如对已存在的聚合法再次执行创建型命令）。 */
    COMMAND_REJECTED(ErrorCategory.CLIENT_ERROR, false),

    /** 存储中的事件流不完整或版本不自洽。 */
    CORRUPT_STREAM(ErrorCategory.BACKEND_ERROR, false),

    /** 没有可用的事件存储（装配缺失）。 */
    STORE_UNAVAILABLE(ErrorCategory.CONFIG_ERROR, false),

    /** 其他内部错误。 */
    INTERNAL_ERROR(ErrorCategory.BACKEND_ERROR, false);

    private final ErrorCategory category;
    private final boolean defaultRetryable;

    CqrsErrorCode(ErrorCategory category, boolean defaultRetryable) {
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
