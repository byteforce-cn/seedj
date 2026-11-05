package cn.byteforce.seedj.eventbus.spi;

/**
 * 领域事件 payload 的序列化方式。
 *
 * <p>业务方可以注册自己的实现（例如 Avro / Protobuf）；不注册时内置实现使用
 * 应用上下文中的 Jackson {@code ObjectMapper}。
 *
 * <p>消费侧必须同时实现 {@link #deserialize}：发布与消费使用同一个 codec，
 * 否则消息无法还原为业务事件。
 */
public interface EventPayloadSerializer {

    byte[] serialize(Object payload);

    /**
     * 把 payload 还原成业务事件。默认实现不支持反序列化，消费侧会明确报
     * {@code NO_SERIALIZER}，而不是静默丢弃消息。
     */
    default <T> T deserialize(byte[] payload, Class<T> type) {
        throw new cn.byteforce.seedj.eventbus.EventBusException(
                cn.byteforce.seedj.eventbus.EventBusErrorCode.NO_SERIALIZER,
                "the configured EventPayloadSerializer does not support deserialization");
    }
}
