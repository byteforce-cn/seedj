package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.eventbus.EventEnvelope;
import cn.byteforce.seedj.eventbus.EventSubscriber;
import cn.byteforce.seedj.eventbus.EventSubscription;
import cn.byteforce.seedj.im.ImMessage;
import cn.byteforce.seedj.im.spi.ImMessageStore;
import cn.byteforce.seedj.starter.im.protocol.Cmd;
import cn.byteforce.seedj.starter.im.protocol.body.EditNotify;
import java.util.Optional;
import java.util.UUID;

/**
 * 消费其它节点的投递意向：本节点有目标连接才真正下发。
 *
 * <p>订阅组按节点 id 区分，因此每个节点都收到一份（广播语义）。消息正文按 {@code messageId} 回读，
 * 节点之间共享同一份消息存储；内存存储下回读不到，投递跳过并留给客户端增量拉取。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class ImDeliverySubscriber implements EventSubscriber<ImDeliveryEvent> {

    private final String nodeId;

    private final ImPushProvider local;

    private final ImMessagingService messaging;

    private final ImMessageStore messages;

    ImDeliverySubscriber(
            String nodeId,
            ImPushProvider local,
            ImMessagingService messaging,
            ImMessageStore messages) {
        this.nodeId = nodeId;
        this.local = local;
        this.messaging = messaging;
        this.messages = messages;
    }

    @Override
    public EventSubscription subscription() {
        return EventSubscription.of("im-delivery-" + nodeId, ImDeliveryEvent.TOPIC);
    }

    @Override
    public void onEvent(EventEnvelope<ImDeliveryEvent> envelope) {
        ImDeliveryEvent event = envelope.event();
        if (nodeId.equals(event.origin()) || event.recipients().isEmpty()) {
            return;
        }
        byte cmd = (byte) event.cmd();
        if (event.messageId() == null) {
            local.deliverMany(event.recipients(), cmd, event.body());
            return;
        }
        Optional<ImMessage> message = find(event.messageId());
        if (message.isEmpty()) {
            return;
        }
        ImMessage found = message.get();
        if (cmd == Cmd.EDIT_REQ) {
            local.deliverMany(event.recipients(), cmd, editNotice(found));
        } else {
            local.deliverMany(event.recipients(), cmd, messaging.toDeliver(found));
        }
    }

    private static EditNotify editNotice(ImMessage message) {
        return new EditNotify(
                message.conversationId().toString(),
                message.id().toString(),
                message.seq(),
                message.senderId(),
                message.body(),
                message.editedAt() == null ? 0L : message.editedAt().toEpochMilli());
    }

    private Optional<ImMessage> find(String messageId) {
        try {
            return messages.find(UUID.fromString(messageId));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }
}
