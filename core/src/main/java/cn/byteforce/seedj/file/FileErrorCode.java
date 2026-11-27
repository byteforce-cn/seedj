package cn.byteforce.seedj.file;

import cn.byteforce.seedj.core.ErrorCategory;
import cn.byteforce.seedj.core.ErrorDescriptor;

/**
 * 文件能力错误码。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum FileErrorCode implements ErrorDescriptor {

    /** key 为空、过长、含控制字符或是非法路径段。 */
    INVALID_KEY(ErrorCategory.CLIENT_ERROR, false),

    /** 空文件、长度不符或 content-type 过长。 */
    INVALID_REQUEST(ErrorCategory.CLIENT_ERROR, false),

    /** 目标文件不存在。 */
    NOT_FOUND(ErrorCategory.CLIENT_ERROR, false),

    /** create-only 冲突：key 已存在。 */
    ALREADY_EXISTS(ErrorCategory.CLIENT_ERROR, false),

    /** 超过 provider 的单文件上限或存储配额。 */
    TOO_LARGE(ErrorCategory.LIMIT_EXCEEDED, false),

    /** 存储服务不可达（网络、agent、选主、安全时间戳滞后）。 */
    PROVIDER_UNAVAILABLE(ErrorCategory.BACKEND_ERROR, true),

    /** 其他内部错误（元数据损坏、元数据与底层数据不一致）。 */
    INTERNAL_ERROR(ErrorCategory.BACKEND_ERROR, false);

    private final ErrorCategory category;
    private final boolean defaultRetryable;

    FileErrorCode(ErrorCategory category, boolean defaultRetryable) {
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
