package cn.byteforce.seedj.starter.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
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
import cn.byteforce.seedj.starter.im.protocol.body.SessionSyncResp;
import cn.byteforce.seedj.starter.im.web.AddMemberRequest;
import cn.byteforce.seedj.starter.im.web.CreateConversationRequest;
import cn.byteforce.seedj.starter.im.web.ImAdminController;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 管理端点：建单聊/群聊、列会话、加人与退群，以及未认证与非法输入。 */
class ImAdminControllerTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-01T08:00:00Z"), ZoneOffset.UTC);

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
                    new ImPushProvider() {
                        @Override
                        public void deliver(String subjectId, byte cmd, Object body) {}

                        @Override
                        public void deliverMany(
                                java.util.Collection<String> subjectIds, byte cmd, Object body) {}
                    },
                    policy,
                    TransactionRunner.direct(),
                    OutboxWriter.discard(),
                    ImProperties.defaults(),
                    new InMemoryImCardActionStore(),
                    new ImCardActionRegistry(List.of()));
    private final Caller alice = caller("u-1");

    private ImAdminController controller;
    private boolean authenticated = true;

    @BeforeEach
    void setUp() {
        controller =
                new ImAdminController(
                        () -> authenticated ? Optional.of(alice) : Optional.empty(),
                        conversationService,
                        messaging);
    }

    @Test
    @DisplayName("建单聊返回会话视图，并在列表里可见")
    void createsAndListsSingleConversation() {
        SessionSyncResp.ConversationView view =
                controller.create(new CreateConversationRequest("single", null, "u-2", null));

        assertThat(view.kind()).isEqualTo("SINGLE");
        assertThat(view.conversationId()).isNotBlank();
        assertThat(controller.list()).hasSize(1);
        assertThat(controller.list().get(0).unread()).isZero();
    }

    @Test
    @DisplayName("建群、加人、退群走同一条服务路径")
    void managesGroupMembership() {
        SessionSyncResp.ConversationView group =
                controller.create(
                        new CreateConversationRequest("group", "研发", null, List.of("u-2")));
        assertThat(group.kind()).isEqualTo("GROUP");
        assertThat(group.title()).isEqualTo("研发");

        controller.addMember(group.conversationId(), new AddMemberRequest("u-3", "ADMIN"));
        assertThat(conversations.member(java.util.UUID.fromString(group.conversationId()), "u-3"))
                .isPresent()
                .get()
                .extracting(member -> member.role().name())
                .isEqualTo("ADMIN");

        controller.removeMember(group.conversationId(), "u-3");
        assertThat(conversations.member(java.util.UUID.fromString(group.conversationId()), "u-3"))
                .isEmpty();
    }

    @Test
    @DisplayName("未认证与非法输入按契约错误码拒绝")
    void rejectsInvalidRequests() {
        assertThatThrownBy(
                        () ->
                                controller.create(
                                        new CreateConversationRequest("p2p", null, "u-2", null)))
                .isInstanceOf(ContractException.class)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.INVALID_PAYLOAD);

        assertThatThrownBy(() -> controller.create(null))
                .isInstanceOf(ContractException.class)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.INVALID_PAYLOAD);

        assertThatThrownBy(
                        () -> controller.addMember("not-a-uuid", new AddMemberRequest("u-2", null)))
                .isInstanceOf(ContractException.class)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.INVALID_PAYLOAD);

        authenticated = false;
        assertThatThrownBy(controller::list)
                .isInstanceOf(ContractException.class)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("找不到的会话报 404")
    void unknownConversationIs404() {
        assertThatThrownBy(
                        () ->
                                controller.addMember(
                                        java.util.UUID.randomUUID().toString(),
                                        new AddMemberRequest("u-2", "MEMBER")))
                .isInstanceOf(ContractException.class)
                .extracting(error -> ((ContractException) error).code())
                .isEqualTo(ContractErrorCode.OBJECT_NOT_FOUND);
    }

    private static Caller caller(String id) {
        return new Caller(id, id, "t1", null, null, null, null, null, Map.of());
    }
}
