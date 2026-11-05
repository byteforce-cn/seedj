package cn.byteforce.seedj.pki;

import cn.byteforce.seedj.core.ErrorCategory;
import cn.byteforce.seedj.core.ErrorDescriptor;

/**
 * PKI 错误码。
 *
 * <p>只覆盖契约层能观察到的失败；具体 CA 的错误信息放在异常的 message 里。
 */
public enum PkiErrorCode implements ErrorDescriptor {

    /** 请求参数不合法（alias 格式、subject 为空等）。 */
    INVALID_REQUEST(ErrorCategory.CLIENT_ERROR, false),

    /** 序列号或 alias 不存在。 */
    NOT_FOUND(ErrorCategory.CLIENT_ERROR, false),

    /** 证书或身份已被吊销，不静默恢复。 */
    REVOKED(ErrorCategory.CLIENT_ERROR, false),

    /** 证书已过期且无法自动续期。 */
    EXPIRED(ErrorCategory.CLIENT_ERROR, false),

    /** CA 密钥库不可用（文件缺失、口令错误）。 */
    CA_UNAVAILABLE(ErrorCategory.BACKEND_ERROR, false),

    /** 状态存储（签发/吊销账本）暂时不可读写。 */
    STATE_UNAVAILABLE(ErrorCategory.BACKEND_ERROR, true),

    /** provider 已关闭。 */
    PROVIDER_CLOSED(ErrorCategory.CONFIG_ERROR, false),

    /** 加密或证书操作失败。 */
    INTERNAL_ERROR(ErrorCategory.BACKEND_ERROR, false);

    private final ErrorCategory category;
    private final boolean defaultRetryable;

    PkiErrorCode(ErrorCategory category, boolean defaultRetryable) {
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
