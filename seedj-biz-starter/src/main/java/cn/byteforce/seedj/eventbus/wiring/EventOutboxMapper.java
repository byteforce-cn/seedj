package cn.byteforce.seedj.eventbus.wiring;

import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.outbox.OutboxRecord;

/**
 * eventbus 与 outbox 之间的唯一映射点。
 *
 * <p>eventbus 把 topic / eventType / eventVersion 交给 outbox 的
 * destination / contentType / contentVersion；outbox 不认识事件语义。
 */
final class EventOutboxMapper {

    private EventOutboxMapper() {
    }

    static OutboxRecord toRecord(EventMessage message) {
        return new OutboxRecord(
                message.eventId(),
                message.topic(),
                message.partitionKey(),
                message.eventType(),
                message.eventVersion(),
                message.payload(),
                message.headers(),
                message.occurredAt());
    }

    static EventMessage toMessage(OutboxRecord record) {
        return new EventMessage(
                record.messageId(),
                record.destination(),
                record.partitionKey(),
                record.contentType(),
                record.contentVersion(),
                record.payload(),
                record.headers(),
                record.occurredAt());
    }
}
