package cn.byteforce.seedj.core;

/** 基础设施生命周期与迁移相关的错误码。 */
public enum InfrastructureErrorCode implements ErrorDescriptor {

    /** 必需能力初始化失败。 */
    INITIALIZATION_FAILED(ErrorCategory.CONFIG_ERROR, false),

    /** 迁移脚本执行失败。 */
    MIGRATION_FAILED(ErrorCategory.CONFIG_ERROR, false),

    /** 已应用迁移的 checksum 与当前代码不一致。 */
    MIGRATION_CHECKSUM_MISMATCH(ErrorCategory.CONFIG_ERROR, false),

    /** 存在尚未应用的本地迁移。 */
    MIGRATION_PENDING(ErrorCategory.CONFIG_ERROR, true),

    /** 数据库中存在本地代码不认识的迁移版本。 */
    MIGRATION_UNKNOWN_VERSION(ErrorCategory.CONFIG_ERROR, false),

    /** 初始化后能力仍不可用。 */
    NOT_READY(ErrorCategory.CONFIG_ERROR, true);

    private final ErrorCategory category;
    private final boolean defaultRetryable;

    InfrastructureErrorCode(ErrorCategory category, boolean defaultRetryable) {
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
