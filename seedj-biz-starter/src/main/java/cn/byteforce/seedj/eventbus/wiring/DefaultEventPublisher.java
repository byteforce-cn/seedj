package cn.byteforce.seedj.eventbus.wiring;

import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.eventbus.EventBusErrorCode;
import cn.byteforce.seedj.eventbus.EventBusException;
import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.EventPublisher;
import cn.byteforce.seedj.eventbus.spi.EventBusTransport;
import cn.byteforce.seedj.eventbus.spi.EventPayloadSerializer;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.observability.wiring.TracePropagation;
import cn.byteforce.seedj.outbox.OutboxWriter;
import cn.byteforce.seedj.outbox.spi.OutboxStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.CompletionException;

/**
 * 默认发布实现。
 *
 * <p>存在 outbox 能力（同时有 {@link OutboxStore} 与 {@link OutboxWriter}）时：
 * 写入 outbox（与业务同事务），由 outbox relay 异步投递；没有 outbox 时：
 * 直接写 transport（at-most-once）。依赖用 {@link ObjectProvider} 延迟解析，
 * 所以 bean 的注册顺序不影响装配。</p>
 *
 * <p>追踪上下文在这里注入 {@code EventMessage.headers}：此刻仍在命令事务内，
 * header 会随 outbox 落库，再由 relay 原样带到消费端。</p>
 */
public class DefaultEventPublisher implements EventPublisher {

    private final ObjectProvider<OutboxStore> outboxStores;
    private final ObjectProvider<OutboxWriter> outboxWriters;
    private final ObjectProvider<EventBusTransport> transport;
    private final ObjectProvider<EventPayloadSerializer> serializer;
    private final IdGenerator<String> idGenerator;
    private final EventBusMetrics metrics;
    private final TracePropagation tracing;

    public DefaultEventPublisher(ObjectProvider<OutboxStore> outboxStores,
                                 ObjectProvider<OutboxWriter> outboxWriters,
                                 ObjectProvider<EventBusTransport> transport,
                                 ObjectProvider<EventPayloadSerializer> serializer,
                                 IdGenerator<String> idGenerator,
                                 EventBusMetrics metrics,
                                 TracePropagation tracing) {
        this.outboxStores = outboxStores;
        this.outboxWriters = outboxWriters;
        this.transport = transport;
        this.serializer = serializer;
        this.idGenerator = idGenerator;
        this.metrics = metrics;
        this.tracing = tracing;
    }

    @Override
    public void publish(DomainEvent event) {
        EventPayloadSerializer payloadSerializer = serializer.getIfAvailable();
        if (payloadSerializer == null) {
            throw new EventBusException(EventBusErrorCode.NO_SERIALIZER,
                    "no EventPayloadSerializer bean; register one or add Jackson to the classpath");
        }
        publish(EventMessage.from(idGenerator.id(), event, payloadSerializer.serialize(event)));
    }

    @Override
    public void publish(EventMessage message) {
        EventMessage traced = message.withHeaders(tracing.inject(message.headers()));
        try {
            OutboxStore store = outboxStores.getIfAvailable();
            OutboxWriter writer = outboxWriters.getIfAvailable();
            if (store != null && writer != null) {
                writer.append(EventOutboxMapper.toRecord(traced));
                metrics.published(traced.eventType(), true);
                return;
            }

            EventBusTransport busTransport = transport.getIfAvailable();
            if (busTransport == null) {
                throw new EventBusException(EventBusErrorCode.NO_TRANSPORT,
                        "no OutboxStore and no EventBusTransport on the classpath");
            }
            busTransport.send(traced).toCompletableFuture().join();
            metrics.published(traced.eventType(), false);
        } catch (CompletionException e) {
            metrics.publishFailed(traced.eventType());
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof EventBusException busException) {
                throw busException;
            }
            throw new EventBusException(EventBusErrorCode.SEND_FAILED,
                    "transport failed: " + cause.getMessage(), cause);
        } catch (RuntimeException e) {
            metrics.publishFailed(traced.eventType());
            throw e;
        }
    }
}
