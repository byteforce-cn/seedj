package cn.byteforce.seedj.starter.im;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.core.clock.Clocks;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.im.ImMessage;
import cn.byteforce.seedj.im.ImMessageKind;
import cn.byteforce.seedj.im.spi.ImConversationStore;
import cn.byteforce.seedj.im.spi.ImMessageStore;
import cn.byteforce.seedj.outbox.OutboxWriter;
import cn.byteforce.seedj.starter.im.protocol.Cmd;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 系统通知：单聊复用、卡片幂等、非法卡片降级文本、发送后推送。 */
class ImSystemNotifierTest {

    private static final String TENANT = "t1";
    private static final String RECIPIENT = "u-2";

    private final java.time.Clock storeClock =
            java.time.Clock.fixed(
                    java.time.Instant.parse("2026-11-19T00:00:00Z"), java.time.ZoneOffset.UTC);
    private final cn.byteforce.seedj.core.clock.Clock clock = Clocks.system();
    private final cn.byteforce.seedj.infra.local.im.AtomicLongSequenceAllocator numbers =
            new cn.byteforce.seedj.infra.local.im.AtomicLongSequenceAllocator();
    private final ImConversationStore conversations =
            new cn.byteforce.seedj.infra.local.im.InMemoryImConversationStore(numbers, storeClock);
    private final ImMessageStore messages =
            new cn.byteforce.seedj.infra.local.im.InMemoryImMessageStore(storeClock);
    private final RecordingPush push = new RecordingPush();

    private ImSystemNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier =
                new ImSystemNotifier(
                        conversations,
                        messages,
                        numbers,
                        push,
                        TransactionRunner.direct(),
                        OutboxWriter.discard(),
                        clock,
                        new ImSystemProperties("system"),
                        ImProperties.defaults());
    }

    @Test
    @DisplayName("同一收件人复用同一个系统单聊")
    void reusesSystemConversation() {
        ImMessage first = notifier.sendCard(TENANT, RECIPIENT, card("c-1"));
        ImMessage second = notifier.sendCard(TENANT, RECIPIENT, card("c-2"));

        assertThat(first.conversationId()).isEqualTo(second.conversationId());
        assertThat(first.kind()).isEqualTo(ImMessageKind.CARD);
        assertThat(first.senderId()).isEqualTo("system");
        assertThat(push.deliveries)
                .containsExactly(
                        RECIPIENT + "|" + Cmd.CHAT_DELIVER, RECIPIENT + "|" + Cmd.CHAT_DELIVER);
    }

    @Test
    @DisplayName("同一 card_id 重发幂等")
    void sameCardIdIsIdempotent() {
        ImMessage first = notifier.sendCard(TENANT, RECIPIENT, card("c-1"));
        ImMessage second = notifier.sendCard(TENANT, RECIPIENT, card("c-1"));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(push.deliveries).hasSize(1);
    }

    @Test
    @DisplayName("卡片体不合法：降级文本，不丢消息")
    void invalidCardFallsBackToText() {
        CardMessage invalid =
                new CardMessage(
                        "s",
                        "t",
                        null,
                        "fallback",
                        "c-3",
                        Map.of("decision_id", "x".repeat(70_000)),
                        List.of(new CardMessage.CardAction("approve", "批准")));

        ImMessage message = notifier.sendCard(TENANT, RECIPIENT, invalid);

        assertThat(message.kind()).isEqualTo(ImMessageKind.TEXT);
        assertThat(message.body()).containsEntry("text", "fallback");
    }

    private static CardMessage card(String cardId) {
        return new CardMessage(
                "agent.approval.card.v1",
                "审批",
                "请审批",
                "请审批",
                cardId,
                Map.of("decision_id", "d-1"),
                List.of(new CardMessage.CardAction("approve", "批准")));
    }

    private static final class RecordingPush implements ImPushProvider {
        private final List<String> deliveries = new java.util.ArrayList<>();

        @Override
        public void deliver(String subjectId, byte cmd, Object body) {
            deliveries.add(subjectId + "|" + cmd);
        }

        @Override
        public void deliverMany(java.util.Collection<String> subjectIds, byte cmd, Object body) {
            subjectIds.forEach(id -> deliver(id, cmd, body));
        }
    }
}
