package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.EventPublisher;
import cn.byteforce.seedj.im.spi.ImMessageStore;
import cn.byteforce.seedj.starter.im.protocol.body.ChatDeliver;
import cn.byteforce.seedj.starter.im.protocol.body.EditNotify;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * coord 模式的跨节点扇出：把投递意向写进 outbox，由 EventBus 送到每个节点。
 *
 * <p>发布失败只记日志：消息已经提交，客户端下次增量拉取会补齐；把投递失败冒泡成命令失败会让 客户端重发、产生重复消息。{@code origin}
 * 用于让发出节点跳过自己的事件——本地投递已经做过一次。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class ImCoordPush {

    private static final Logger log = LoggerFactory.getLogger(ImCoordPush.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final String nodeId;

    private final EventPublisher publisher;

    private final TransactionRunner transaction;

    private final ImPushProvider local;

    ImCoordPush(
            String nodeId,
            EventPublisher publisher,
            TransactionRunner transaction,
            ImPushProvider local) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.local = Objects.requireNonNull(local, "local");
    }

    String nodeId() {
        return nodeId;
    }

    void relay(Collection<String> subjects, byte cmd, Object body) {
        if (subjects == null || subjects.isEmpty()) {
            return;
        }
        List<String> recipients =
                subjects.stream()
                        .filter(subject -> subject != null && !subject.isBlank())
                        .distinct()
                        .toList();
        if (recipients.isEmpty()) {
            return;
        }
        String messageId =
                switch (body) {
                    case ChatDeliver deliver -> deliver.messageId();
                    case EditNotify edit -> edit.messageId();
                    case null, default -> null;
                };
        Map<String, Object> payload =
                messageId == null && body != null ? JSON.convertValue(body, MAP) : Map.of();
        ImDeliveryEvent event =
                new ImDeliveryEvent(
                        nodeId, Byte.toUnsignedInt(cmd), recipients, messageId, payload);
        String eventId = UUID.randomUUID().toString();
        try {
            transaction.inTransaction(
                    () -> {
                        publisher.publish(
                                new EventMessage(
                                        eventId,
                                        ImDeliveryEvent.TOPIC,
                                        null,
                                        ImDeliveryEvent.TOPIC,
                                        1,
                                        JSON.writeValueAsBytes(event),
                                        Map.of(),
                                        Instant.now()));
                        return null;
                    });
        } catch (RuntimeException failure) {
            log.warn("IM 跨节点投递发布失败，降级为仅本地投递: cmd={}, error={}", cmd, failure.getMessage());
        }
    }

    ImDeliverySubscriber subscriber(ImMessagingService messaging, ImMessageStore messages) {
        return new ImDeliverySubscriber(nodeId, local, messaging, messages);
    }
}
