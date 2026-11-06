package cn.byteforce.seedj.starter.eventbus;

import cn.byteforce.seedj.eventbus.spi.EventBusTransport;
import cn.byteforce.seedj.outbox.OutboxErrorCode;
import cn.byteforce.seedj.outbox.OutboxException;
import cn.byteforce.seedj.outbox.OutboxRecord;
import cn.byteforce.seedj.outbox.spi.OutboxSink;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * EventBus 作为 outbox 投递目标的适配器。
 *
 * <p>把 {@link OutboxRecord} 映射回 {@link cn.byteforce.seedj.eventbus.EventMessage}，
 * 再交给 {@link EventBusTransport}。eventbus 不认识 outbox 的存储/租约，
 * outbox 也不认识事件模型。
 */
public final class EventBusOutboxSink implements OutboxSink {

    private final ObjectProvider<EventBusTransport> transports;

    public EventBusOutboxSink(ObjectProvider<EventBusTransport> transports) {
        this.transports = transports;
    }

    @Override
    public CompletionStage<Void> deliver(OutboxRecord record) {
        EventBusTransport transport = transports.getIfAvailable();
        if (transport == null) {
            return CompletableFuture.failedFuture(new OutboxException(
                    OutboxErrorCode.DELIVERY_FAILED,
                    "no EventBusTransport on the classpath; cannot deliver event "
                            + record.messageId()));
        }
        return transport.send(EventOutboxMapper.toMessage(record));
    }
}
