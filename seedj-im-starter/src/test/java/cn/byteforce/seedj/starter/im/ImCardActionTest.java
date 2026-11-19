package cn.byteforce.seedj.starter.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
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
import cn.byteforce.seedj.starter.im.protocol.body.CardActionReq;
import cn.byteforce.seedj.starter.im.protocol.body.ChatSendReq;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 卡片动作回传：成员校验、动作存在性、幂等与失败重试。 */
class ImCardActionTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-19T00:00:00Z"), ZoneOffset.UTC);

    private final ImSequenceAllocator numbers = new AtomicLongSequenceAllocator();
    private final ImConversationStore conversations =
            new InMemoryImConversationStore(numbers, CLOCK);
    private final ImMessageStore messages = new InMemoryImMessageStore(CLOCK);
    private final ImCursorStore cursors = new InMemoryImCursorStore();
    private final ImConversationPolicy policy = new ImConversationPolicy() {};
    private final ImConversationService conversationService =
            new ImConversationService(conversations, (caller, peer) -> true, policy, CLOCK);
    private final AtomicInteger handled = new AtomicInteger();
    private final ImCardActionHandler handler =
            new ImCardActionHandler() {
                @Override
                public String schema() {
                    return "agent.approval.card.v1";
                }

                @Override
                public Map<String, Object> handle(ImCardActionContext context) {
                    handled.incrementAndGet();
                    return Map.of("status", "approved", "decision_id", "d-1");
                }
            };

    private ImMessagingService messaging;
    private ImMessage card;
    private Caller alice;
    private Caller bob;

    @BeforeEach
    void setUp() {
        messaging =
                new ImMessagingService(
                        conversationService,
                        conversations,
                        messages,
                        cursors,
                        numbers,
                        new NoopPush(),
                        policy,
                        TransactionRunner.direct(),
                        OutboxWriter.discard(),
                        ImProperties.defaults(),
                        new InMemoryImCardActionStore(),
                        new ImCardActionRegistry(List.of(handler)));
        alice = caller("u-1", "alice");
        bob = caller("u-2", "bob");
        card =
                messaging.send(
                        alice,
                        new ChatSendReq(
                                "c-1",
                                null,
                                "u-2",
                                "CARD",
                                Map.of(
                                        "schema",
                                        "agent.approval.card.v1",
                                        "title",
                                        "审批",
                                        "fallback_text",
                                        "请审批",
                                        "actions",
                                        List.of(Map.of("id", "approve", "label", "批准")))));
    }

    @Test
    @DisplayName("首次回传调用 handler，重复回传返回首次结果")
    void actOnCardIsIdempotent() {
        Map<String, Object> first =
                messaging.actOnCard(bob, new CardActionReq(card.id().toString(), "approve"));
        Map<String, Object> second =
                messaging.actOnCard(bob, new CardActionReq(card.id().toString(), "approve"));

        assertThat(first).containsEntry("status", "approved");
        assertThat(second).isEqualTo(first);
        assertThat(handled.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("非成员 403")
    void nonMemberIsForbidden() {
        Caller stranger = caller("u-3", "carol");

        assertThatThrownBy(
                        () ->
                                messaging.actOnCard(
                                        stranger,
                                        new CardActionReq(card.id().toString(), "approve")))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> assertThat(error.code()).isEqualTo(ContractErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("未知 action_id 400")
    void unknownActionIsInvalidPayload() {
        assertThatThrownBy(
                        () ->
                                messaging.actOnCard(
                                        bob, new CardActionReq(card.id().toString(), "delete")))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(ContractErrorCode.INVALID_PAYLOAD));
    }

    private static Caller caller(String id, String username) {
        return new Caller(id, username, "t1", Set.of(), Set.of(), Set.of(), Set.of(), null, null);
    }

    private static final class NoopPush implements ImPushProvider {
        @Override
        public void deliver(String subjectId, byte cmd, Object body) {}

        @Override
        public void deliverMany(Collection<String> subjectIds, byte cmd, Object body) {}
    }
}
