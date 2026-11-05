package cn.byteforce.seedj.eventbus;

import cn.byteforce.seedj.core.ErrorCategory;
import cn.byteforce.seedj.core.ErrorDescriptor;

/**
 * EventBus 错误码（transport 与消费侧）。
 *
 * <p>outbox 的错误码在 {@link cn.byteforce.seedj.outbox.OutboxErrorCode}，两者不共用枚举。
 */
public enum EventBusErrorCode implements ErrorDescriptor {

    /** 消费租约已失效，凭证不再可用。 */
    LEASE_LOST(ErrorCategory.CLIENT_ERROR, false),

    /** 没有可用的投递通道（既没有 outbox 也没有 transport）。 */
    NO_TRANSPORT(ErrorCategory.CONFIG_ERROR, false),

    /** 没有可用的 payload 序列化器。 */
    NO_SERIALIZER(ErrorCategory.CONFIG_ERROR, false),

    /** payload 序列化/反序列化失败。 */
    SERIALIZATION_FAILED(ErrorCategory.CLIENT_ERROR, false),

    /** 没有可用的消费存储。 */
    NO_CONSUMER_STORE(ErrorCategory.CONFIG_ERROR, false),

    /** 订阅声明不合法（无法解析事件类型、group 为空等）。 */
    INVALID_SUBSCRIBER(ErrorCategory.CONFIG_ERROR, false),

    /** 消费处理器执行失败。 */
    CONSUMER_FAILED(ErrorCategory.BACKEND_ERROR, true),

    /** transport 存储不可用（文件 I/O、目录损坏等）。 */
    TRANSPORT_UNAVAILABLE(ErrorCategory.BACKEND_ERROR, true),

    /** 投递到 transport 失败。 */
    SEND_FAILED(ErrorCategory.BACKEND_ERROR, true),

    /** provider 已关闭。 */
    PROVIDER_CLOSED(ErrorCategory.CONFIG_ERROR, false),

    /** 其他内部错误。 */
    INTERNAL_ERROR(ErrorCategory.BACKEND_ERROR, false);

    private final ErrorCategory category;
    private final boolean defaultRetryable;

    EventBusErrorCode(ErrorCategory category, boolean defaultRetryable) {
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
