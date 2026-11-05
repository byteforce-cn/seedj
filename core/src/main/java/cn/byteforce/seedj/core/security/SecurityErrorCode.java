package cn.byteforce.seedj.core.security;

import cn.byteforce.seedj.core.ErrorCategory;
import cn.byteforce.seedj.core.ErrorDescriptor;

/**
 * 安全契约的稳定错误码。
 *
 * <p>错误项名称（{@link #name()}）进入响应体与日志，<b>不得</b>随重构变化；
 * 与 {@code IamErrorCode} 的分工：这里是"资源服务器侧"的通用失败，
 * IAM 侧的 {@code INVALID_CREDENTIALS}/{@code FORBIDDEN} 等仍然属于认证服务器语义。</p>
 *
 * <p>四类失败都不可重试：原样重放不会改变结果，调用方应重新认证或放弃。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum SecurityErrorCode implements ErrorDescriptor {

    /** 缺少凭证：匿名调用需要认证的端点。 */
    AUTHENTICATION_REQUIRED(ErrorCategory.CLIENT_ERROR, false),

    /** 凭证无效：签名、类型、issuer、受众任一不满足。 */
    AUTHENTICATION_FAILED(ErrorCategory.CLIENT_ERROR, false),

    /** 凭证已过期（含允许的时钟偏移）。 */
    TOKEN_EXPIRED(ErrorCategory.CLIENT_ERROR, false),

    /** 已认证但没有目标资源的权限。 */
    ACCESS_DENIED(ErrorCategory.CLIENT_ERROR, false);

    private final ErrorCategory category;
    private final boolean defaultRetryable;

    SecurityErrorCode(ErrorCategory category, boolean defaultRetryable) {
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

    /** 建议的 HTTP 状态：认证失败 401，权限不足 403。 */
    public int httpStatus() {
        return this == ACCESS_DENIED ? 403 : 401;
    }
}
