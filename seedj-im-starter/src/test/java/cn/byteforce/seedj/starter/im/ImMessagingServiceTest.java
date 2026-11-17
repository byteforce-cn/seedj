package cn.byteforce.seedj.starter.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.im.ImConversation;
import cn.byteforce.seedj.im.ImMemberRole;
import cn.byteforce.seedj.im.ImMessage;
import cn.byteforce.seedj.im.ImMessageKind;
import cn.byteforce.seedj.im.spi.ImConversationStore;
import cn.byteforce.seedj.im.spi.ImCursorStore;
import cn.byteforce.seedj.im.spi.ImMessageStore;
import cn.byteforce.seedj.im.spi.ImSequenceAllocator;
import cn.byteforce.seedj.infra.local.im.AtomicLongSequenceAllocator;
import cn.byteforce.seedj.infra.local.im.InMemoryImConversationStore;
import cn.byteforce.seedj.infra.local.im.InMemoryImCursorStore;
import cn.byteforce.seedj.infra.local.im.InMemoryImMessageStore;
import cn.byteforce.seedj.outbox.OutboxWriter;
import cn.byteforce.seedj.starter.im.protocol.Cmd;
import cn.byteforce.seedj.starter.im.protocol.body.AckReq;
import cn.byteforce.seedj.starter.im.protocol.body.ChatSendReq;
import cn.byteforce.seedj.starter.im.protocol.body.OfflinePullReq;
import cn.byteforce.seedj.starter.im.protocol.body.OfflinePullResp;
import cn.byteforce.seedj.starter.im.protocol.body.ReadReceiptReq;
import cn.byteforce.seedj.starter.im.protocol.body.SessionSyncResp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 消息服务：单聊自动建会话、幂等、权限、补拉、已读与同步。 */
class ImMessagingServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-01T09:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ImSequenceAllocator numbers = new AtomicLongSequenceAllocator();
    private final ImConversationStore conversations =
            new InMemoryImConversationStore(numbers, clock);
    private final ImMessageStore messages = new InMemoryImMessageStore(clock);
    private final ImCursorStore cursors = new InMemoryImCursorStore();
    private final RecordingPush push = new RecordingPush();
    private final ImConversationPolicy policy = new ImConversationPolicy() {};

    private ImConversationService conversationService;
    private ImMessagingService messaging;

    private final Caller alice = caller("u-1", "alice", "t1");
    private final Caller bob = caller("u-2", "bob", "t1");

    @BeforeEach
    void setUp() {
        conversationService =
                new ImConversationService(conversations, (c, p) -> true, policy, clock);
        messaging = newService(policy, conversationService);
    }

    @Test
    @DisplayName("首条消息按 peer_id 自动建单聊，且只推给对端")
    void firstMessageCreatesConversationAndPushesToPeer() {
        ImMessage message =
                messaging.send(
                        alice, new ChatSendReq("c-1", null, "u-2", "TEXT", Map.of("text", "hi")));

        assertThat(message.seq()).isPositive();
        assertThat(message.kind()).isEqualTo(ImMessageKind.TEXT);
        ImConversation conversation = conversations.find(message.conversationId()).orElseThrow();
        assertThat(conversation.kind().name()).isEqualTo("SINGLE");
        assertThat(conversation.directKey())
                .isEqualTo(ImConversation.directKey(alice.id(), bob.id()));
        assertThat(conversations.members(conversation.id())).hasSize(2);
        assertThat(push.deliveries()).containsExactly("u-2|" + Cmd.CHAT_DELIVER);
    }

    @Test
    @DisplayName("同一 client_msg_id 重发返回同一条消息，不重复推送")
    void resendIsIdempotent() {
        ChatSendReq request = new ChatSendReq("c-1", null, "u-2", "TEXT", Map.of("text", "hi"));

        ImMessage first = messaging.send(alice, request);
        ImMessage replay = messaging.send(alice, request);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.seq()).isEqualTo(first.seq());
        assertThat(messages.page(first.conversationId(), 0, 10)).hasSize(1);
        assertThat(push.deliveries()).hasSize(1);
    }

    @Test
    @DisplayName("非成员与会话租户不一致都被拒绝")
    void membershipAndTenantAreEnforced() {
        ImMessage message =
                messaging.send(alice, new ChatSendReq("c-1", null, "u-2", "TEXT", Map.of()));
        String conversationId = message.conversationId().toString();
        Caller stranger = caller("u-3", "carol", "t1");
        Caller otherTenant = caller("u-1", "alice", "t2");

        assertThatThrownBy(
                        () ->
                                messaging.send(
                                        stranger,
                                        new ChatSendReq(
                                                "c-2", conversationId, null, "TEXT", Map.of())))
                .isInstanceOf(ContractException.class)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.FORBIDDEN);
        assertThatThrownBy(
                        () ->
                                messaging.send(
                                        otherTenant,
                                        new ChatSendReq(
                                                "c-3", conversationId, null, "TEXT", Map.of())))
                .isInstanceOf(ContractException.class)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("未知类型、超大正文与非法会话标识都是无效载荷")
    void invalidPayloadsAreRejected() {
        assertThatThrownBy(
                        () ->
                                messaging.send(
                                        alice,
                                        new ChatSendReq("c-1", null, "u-2", "VIDEO", Map.of())))
                .isInstanceOf(ContractException.class)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.INVALID_PAYLOAD);

        ImProperties small = smallBodyProperties();
        ImMessagingService tight = newService(policy, conversationService, small);
        assertThatThrownBy(
                        () ->
                                tight.send(
                                        alice,
                                        new ChatSendReq(
                                                "c-2",
                                                null,
                                                "u-2",
                                                "TEXT",
                                                Map.of("text", "x".repeat(200)))))
                .isInstanceOf(ContractException.class)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.INVALID_PAYLOAD);

        assertThatThrownBy(
                        () ->
                                messaging.send(
                                        alice,
                                        new ChatSendReq(
                                                "c-3", "not-a-uuid", null, "TEXT", Map.of())))
                .isInstanceOf(ContractException.class);
        assertThatThrownBy(() -> messaging.send(alice, null)).isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("已读只前进，并向对端转发回执")
    void readAdvancesAndNotifiesPeer() {
        ImMessage message =
                messaging.send(alice, new ChatSendReq("c-1", null, "u-2", "TEXT", Map.of()));
        String conversationId = message.conversationId().toString();
        push.clear();

        long first = messaging.read(bob, new ReadReceiptReq(conversationId, message.seq()));
        long second = messaging.read(bob, new ReadReceiptReq(conversationId, 1));

        assertThat(first).isEqualTo(message.seq());
        assertThat(second).isEqualTo(message.seq());
        assertThat(cursors.readSeq(message.conversationId(), bob.id())).isEqualTo(message.seq());
        assertThat(push.deliveries()).hasSize(2).containsOnly("u-1|" + Cmd.READ_RECEIPT);
    }

    @Test
    @DisplayName("确认推进设备游标，同步用它的结果")
    void ackAdvancesDeviceCursor() {
        ImMessage message =
                messaging.send(alice, new ChatSendReq("c-1", null, "u-2", "TEXT", Map.of()));

        long cursor =
                messaging.ack(
                        bob,
                        "dev-1",
                        new AckReq(message.conversationId().toString(), message.seq()));

        assertThat(cursor).isEqualTo(message.seq());
        assertThat(cursors.syncSeq("t1", bob.id(), "dev-1")).isEqualTo(message.seq());
        assertThat(messaging.sync(bob, "dev-1", null).nextCursor()).isEqualTo(message.seq());
    }

    @Test
    @DisplayName("补拉只看自己所属会话，游标之后的才返回")
    void pullIsScopedToMemberConversations() {
        ImMessage first =
                messaging.send(alice, new ChatSendReq("c-1", null, "u-2", "TEXT", Map.of()));
        messaging.send(alice, new ChatSendReq("c-2", null, "u-3", "TEXT", Map.of()));

        OfflinePullResp page = messaging.pull(bob, new OfflinePullReq(null, null));

        assertThat(page.messages())
                .extracting(deliver -> deliver.messageId())
                .containsExactly(first.id().toString());
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isEqualTo(first.seq());

        OfflinePullResp caughtUp = messaging.pull(bob, new OfflinePullReq(first.seq(), null));
        assertThat(caughtUp.messages()).isEmpty();
        assertThat(caughtUp.nextCursor()).isEqualTo(first.seq());
    }

    @Test
    @DisplayName("会话同步给出最后序号与未读数")
    void syncReportsUnread() {
        ImMessage first =
                messaging.send(alice, new ChatSendReq("c-1", null, "u-2", "TEXT", Map.of()));
        messaging.send(alice, new ChatSendReq("c-2", null, "u-2", "TEXT", Map.of()));
        messaging.read(bob, new ReadReceiptReq(first.conversationId().toString(), first.seq()));

        SessionSyncResp sync = messaging.sync(bob, "dev-1", 0L);

        assertThat(sync.conversations()).hasSize(1);
        SessionSyncResp.ConversationView view = sync.conversations().get(0);
        assertThat(view.lastSeq()).isGreaterThan(first.seq());
        assertThat(view.unread()).isEqualTo(1);
        assertThat(sync.readCursors()).hasSize(1);
        assertThat(messaging.conversationViews(bob)).hasSize(1);
        assertThat(messaging.toDeliver(first).messageId()).isEqualTo(first.id().toString());
    }

    @Test
    @DisplayName("群会话：成员上限与权限都从这里收口")
    void groupPolicyIsEnforced() {
        ImConversation group = conversationService.createGroup(alice, "研发", List.of("u-2", "u-3"));
        assertThat(conversations.members(group.id())).hasSize(3);
        assertThat(conversationService.memberSubjects(group.id()))
                .containsExactly("u-1", "u-2", "u-3");

        ImConversationPolicy deny =
                new ImConversationPolicy() {
                    @Override
                    public boolean canCreateGroup(Caller caller) {
                        return false;
                    }
                };
        ImConversationService denied =
                new ImConversationService(conversations, (c, p) -> true, deny, clock);
        assertThatThrownBy(() -> denied.createGroup(alice, "x", List.of()))
                .isInstanceOf(ContractException.class)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.FORBIDDEN);

        ImConversationPolicy tiny =
                new ImConversationPolicy() {
                    @Override
                    public int maxMembers() {
                        return 2;
                    }
                };
        ImConversationService limited =
                new ImConversationService(conversations, (c, p) -> true, tiny, clock);
        assertThatThrownBy(() -> limited.createGroup(alice, "x", List.of("u-2", "u-3")))
                .isInstanceOf(ContractException.class)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.INVALID_PAYLOAD);
    }

    @Test
    @DisplayName("退群后不能再发；加人/改名按策略判定")
    void membershipChangesTakeEffect() {
        ImConversation group = conversationService.createGroup(alice, "研发", List.of("u-2"));
        String conversationId = group.id().toString();

        conversationService.addMember(alice, group.id(), "u-3", ImMemberRole.MEMBER);
        assertThat(conversations.members(group.id())).hasSize(3);
        conversationService.rename(alice, group.id(), "平台组");
        assertThat(conversations.find(group.id()).orElseThrow().title()).isEqualTo("平台组");

        conversationService.removeMember(alice, group.id(), "u-3");
        Caller carol = caller("u-3", "carol", "t1");
        assertThatThrownBy(
                        () ->
                                messaging.send(
                                        carol,
                                        new ChatSendReq(
                                                "c-9", conversationId, null, "TEXT", Map.of())))
                .isInstanceOf(ContractException.class)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.FORBIDDEN);

        assertThatThrownBy(
                        () -> conversationService.addMember(alice, UUID.randomUUID(), "u-9", null))
                .isInstanceOf(ContractException.class)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.OBJECT_NOT_FOUND);
    }

    private ImMessagingService newService(
            ImConversationPolicy conversationPolicy, ImConversationService service) {
        return newService(conversationPolicy, service, ImProperties.defaults());
    }

    private ImMessagingService newService(
            ImConversationPolicy conversationPolicy,
            ImConversationService service,
            ImProperties properties) {
        return new ImMessagingService(
                service,
                conversations,
                messages,
                cursors,
                numbers,
                push,
                conversationPolicy,
                TransactionRunner.direct(),
                OutboxWriter.discard(),
                properties);
    }

    private static ImProperties smallBodyProperties() {
        return new ImProperties(
                true,
                ImProperties.Store.MEMORY,
                new ImProperties.Push("local"),
                new ImProperties.Tcp(false, 0),
                new ImProperties.Ws(false, 0, "/ws"),
                null,
                0,
                0,
                null,
                new ImProperties.Message(64, null),
                null,
                null);
    }

    private static Caller caller(String id, String username, String tenant) {
        return new Caller(
                id,
                username,
                tenant,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of("pwd"),
                Caller.DEFAULT_ACR,
                Map.of());
    }

    /** 记录投递的假 provider。 */
    private static final class RecordingPush implements ImPushProvider {

        private final List<String> deliveries = new ArrayList<>();

        @Override
        public void deliver(String subjectId, byte cmd, Object body) {
            deliveries.add(subjectId + "|" + cmd);
        }

        @Override
        public void deliverMany(java.util.Collection<String> subjectIds, byte cmd, Object body) {
            subjectIds.forEach(subjectId -> deliver(subjectId, cmd, body));
        }

        List<String> deliveries() {
            return List.copyOf(deliveries);
        }

        void clear() {
            deliveries.clear();
        }
    }
}
