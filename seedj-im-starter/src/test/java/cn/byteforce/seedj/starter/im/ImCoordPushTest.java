package cn.byteforce.seedj.starter.im;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.eventbus.EventEnvelope;
import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.EventPublisher;
import cn.byteforce.seedj.im.ImMessage;
import cn.byteforce.seedj.im.spi.ImConversationStore;
import cn.byteforce.seedj.im.spi.ImCursorStore;
import cn.byteforce.seedj.im.spi.ImMessageStore;
import cn.byteforce.seedj.im.spi.ImSequenceAllocator;
import cn.byteforce.seedj.infra.local.im.AtomicLongSequenceAllocator;
import cn.byteforce.seedj.infra.local.im.InMemoryImCardActionStore;
import cn.byteforce.seedj.infra.local.im.InMemoryImConversationStore;
import cn.byteforce.seedj.infra.local.im.InMemoryImCursorStore;
import cn.byteforce.seedj.infra.local.im.InMemoryImMessageStore;
import cn.byteforce.seedj.outbox.OutboxWriter;
import cn.byteforce.seedj.starter.im.protocol.Cmd;
import cn.byteforce.seedj.starter.im.protocol.body.ChatDeliver;
import cn.byteforce.seedj.starter.im.protocol.body.ChatSendReq;
import cn.byteforce.seedj.starter.im.protocol.body.EditNotify;
import cn.byteforce.seedj.starter.im.protocol.body.EditReq;
import cn.byteforce.seedj.starter.im.protocol.body.ReadReceiptReq;
import cn.byteforce.seedj.starter.im.protocol.body.RecallReq;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** coord 投递：事件不带正文、按消息 id 回读、发出节点跳过自己的事件。 */
class ImCoordPushTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RecordingPush local = new RecordingPush();
    private final FakePublisher publisher = new FakePublisher();
    private final ImSequenceAllocator numbers = new AtomicLongSequenceAllocator();
    private final ImConversationStore conversations =
            new InMemoryImConversationStore(numbers, CLOCK);
    private final ImMessageStore messages = new InMemoryImMessageStore(CLOCK);
    private final ImCursorStore cursors = new InMemoryImCursorStore();
    private final ImConversationPolicy policy = new ImConversationPolicy() {};
    private final ImConversationService conversationService =
            new ImConversationService(conversations, (caller, peer) -> true, policy, CLOCK);
    private final ImMessagingService messaging =
            new ImMessagingService(
                    conversationService,
                    conversations,
                    messages,
                    cursors,
                    numbers,
                    local,
                    policy,
                    TransactionRunner.direct(),
                    OutboxWriter.discard(),
                    ImProperties.defaults(),
                    new InMemoryImCardActionStore(),
                    new ImCardActionRegistry(List.of()));
    private final ImCoordPush coord =
            new ImCoordPush("node-a", publisher, TransactionRunner.direct(), local);

    @Test
    @DisplayName("消息投递只发 message_id，正文不进事件总线")
    void messageDeliveryCarriesNoBody() {
        ChatDeliver deliver = deliver("u-1", "u-2");

        coord.relay(List.of("u-2"), Cmd.CHAT_DELIVER, deliver);

        ImDeliveryEvent event = publisher.only();
        assertThat(event.origin()).isEqualTo("node-a");
        assertThat(event.cmd()).isEqualTo(Byte.toUnsignedInt(Cmd.CHAT_DELIVER));
        assertThat(event.recipients()).containsExactly("u-2");
        assertThat(event.messageId()).isEqualTo(deliver.messageId());
        assertThat(event.body()).isEmpty();
    }

    @Test
    @DisplayName("收件人去空去重")
    void recipientsAreNormalized() {
        coord.relay(
                java.util.Arrays.asList("u-2", null, " ", "u-2"),
                Cmd.READ_RECEIPT,
                new ReadReceiptReq("c-1", 1));

        assertThat(publisher.only().recipients()).containsExactly("u-2");
    }

    @Test
    @DisplayName("非消息通知带上元数据 body")
    void notificationCarriesBody() {
        coord.relay(List.of("u-2"), Cmd.READ_RECEIPT, new ReadReceiptReq("c-1", 7));

        ImDeliveryEvent event = publisher.only();
        assertThat(event.messageId()).isNull();
        assertThat(event.body()).containsEntry("seq", 7);
    }

    @Test
    @DisplayName("订阅端回读消息并投递；发出节点跳过自己的事件")
    void subscriberReloadsMessageAndSkipsOrigin() {
        ChatDeliver deliver = deliver("u-1", "u-2");
        local.clear();
        ImDeliverySubscriber subscriber = coord.subscriber(messaging, messages);
        ImDeliveryEvent fromOtherNode =
                new ImDeliveryEvent(
                        "node-b",
                        Byte.toUnsignedInt(Cmd.CHAT_DELIVER),
                        List.of("u-2"),
                        deliver.messageId(),
                        Map.of());

        subscriber.onEvent(envelope(fromOtherNode));
        assertThat(local.deliveries()).containsExactly("u-2|" + Cmd.CHAT_DELIVER);
        assertThat(((ChatDeliver) local.details().get(0).body()).body())
                .containsEntry("text", "hi");

        local.clear();
        subscriber.onEvent(
                envelope(
                        new ImDeliveryEvent(
                                "node-a",
                                Byte.toUnsignedInt(Cmd.CHAT_DELIVER),
                                List.of("u-2"),
                                deliver.messageId(),
                                Map.of())));
        assertThat(local.deliveries()).isEmpty();
    }

    @Test
    @DisplayName("编辑通知只带 message_id，正文由接收节点回读")
    void editNotificationReloadsBody() {
        ChatDeliver deliver = deliver("u-1", "u-2");
        local.clear();
        ImMessage edited =
                messaging.edit(
                        caller("u-1"),
                        new EditReq(
                                deliver.conversationId(),
                                deliver.messageId(),
                                Map.of("text", "改过")));
        local.clear();

        coord.relay(
                List.of("u-2"),
                Cmd.EDIT_REQ,
                new EditNotify(
                        deliver.conversationId(),
                        deliver.messageId(),
                        deliver.seq(),
                        "u-1",
                        edited.body(),
                        edited.editedAt().toEpochMilli()));

        ImDeliveryEvent event = publisher.only();
        assertThat(event.messageId()).isEqualTo(deliver.messageId());
        assertThat(event.body()).isEmpty();

        ImDeliverySubscriber subscriber = coord.subscriber(messaging, messages);
        subscriber.onEvent(
                envelope(
                        new ImDeliveryEvent(
                                "node-b",
                                Byte.toUnsignedInt(Cmd.EDIT_REQ),
                                List.of("u-2"),
                                deliver.messageId(),
                                Map.of())));

        EditNotify reloaded = (EditNotify) local.details().get(0).body();
        assertThat(reloaded.body()).containsEntry("text", "改过");
    }

    @Test
    @DisplayName("异步重投的编辑事件不会把已撤回的正文再推出去")
    void editEventAfterRecallLeaksNothing() {
        ChatDeliver deliver = deliver("u-1", "u-2");
        local.clear();
        ImMessage edited =
                messaging.edit(
                        caller("u-1"),
                        new EditReq(
                                deliver.conversationId(),
                                deliver.messageId(),
                                Map.of("text", "改过")));
        assertThat(edited.editedAt()).isNotNull();
        messaging.recall(
                caller("u-1"), new RecallReq(deliver.conversationId(), deliver.messageId()));
        local.clear();

        ImDeliverySubscriber subscriber = coord.subscriber(messaging, messages);
        subscriber.onEvent(
                envelope(
                        new ImDeliveryEvent(
                                "node-b",
                                Byte.toUnsignedInt(Cmd.EDIT_REQ),
                                List.of("u-2"),
                                deliver.messageId(),
                                Map.of())));

        assertThat(local.details()).isEmpty();
    }

    @Test
    @DisplayName("未编辑的消息序列化不出现 edited_at/recalled_at")
    void uneditedDeliveryKeepsWireShape() {
        ChatDeliver deliver = deliver("u-1", "u-2");

        String json = JSON.writeValueAsString(deliver);

        assertThat(json).doesNotContain("edited_at").doesNotContain("recalled_at");
    }

    @Test
    @DisplayName("CoordPushProvider 先投本地，再发跨节点信号")
    void providerFansOutLocallyAndRemotely() {
        ChatDeliver deliver = deliver("u-1", "u-2");
        local.clear();
        CoordPushProvider provider = new CoordPushProvider(local, coord);

        provider.deliverMany(List.of("u-2"), Cmd.CHAT_DELIVER, deliver);

        assertThat(local.deliveries()).containsExactly("u-2|" + Cmd.CHAT_DELIVER);
        assertThat(publisher.only().messageId()).isEqualTo(deliver.messageId());
    }

    private ChatDeliver deliver(String sender, String peer) {
        ImMessage message =
                messaging.send(
                        caller(sender),
                        new ChatSendReq("c-1", null, peer, "TEXT", Map.of("text", "hi")));
        return messaging.toDeliver(message);
    }

    private static EventEnvelope<ImDeliveryEvent> envelope(ImDeliveryEvent event) {
        return new EventEnvelope<>(
                event,
                "e-" + UUID.randomUUID(),
                ImDeliveryEvent.TOPIC,
                "im-delivery-node-a",
                1,
                Instant.now(),
                Map.of());
    }

    private static cn.byteforce.seedj.core.security.Caller caller(String subject) {
        return new cn.byteforce.seedj.core.security.Caller(
                subject,
                subject,
                "default",
                java.util.Set.of(),
                java.util.Set.of(),
                java.util.Set.of(),
                java.util.Set.of("pwd"),
                cn.byteforce.seedj.core.security.Caller.DEFAULT_ACR,
                Map.of());
    }

    /** 记录投递的假 provider。 */
    private static final class RecordingPush implements ImPushProvider {

        private final List<String> deliveries = new ArrayList<>();
        private final List<Delivery> details = new ArrayList<>();

        @Override
        public void deliver(String subjectId, byte cmd, Object body) {
            deliveries.add(subjectId + "|" + cmd);
            details.add(new Delivery(subjectId, cmd, body));
        }

        @Override
        public void deliverMany(Collection<String> subjectIds, byte cmd, Object body) {
            subjectIds.forEach(subjectId -> deliver(subjectId, cmd, body));
        }

        List<String> deliveries() {
            return List.copyOf(deliveries);
        }

        List<Delivery> details() {
            return List.copyOf(details);
        }

        void clear() {
            deliveries.clear();
            details.clear();
        }
    }

    private record Delivery(String subjectId, byte cmd, Object body) {}

    /** 捕获发布消息的假 EventPublisher，并把 payload 解回事件。 */
    private static final class FakePublisher implements EventPublisher {

        private final List<ImDeliveryEvent> events = new ArrayList<>();

        @Override
        public void publish(DomainEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void publish(EventMessage message) {
            events.add(JSON.readValue(message.payload(), ImDeliveryEvent.class));
        }

        ImDeliveryEvent only() {
            assertThat(events).hasSize(1);
            return events.get(0);
        }
    }
}
