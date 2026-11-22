package cn.byteforce.seedj.starter.im;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

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
import cn.byteforce.seedj.starter.im.protocol.Cmd;
import cn.byteforce.seedj.starter.im.protocol.ImCodec;
import cn.byteforce.seedj.starter.im.protocol.ImFrame;
import cn.byteforce.seedj.starter.im.protocol.body.AckReq;
import cn.byteforce.seedj.starter.im.protocol.body.ChatDeliver;
import cn.byteforce.seedj.starter.im.protocol.body.ChatSendReq;
import cn.byteforce.seedj.starter.im.protocol.body.ChatSendResp;
import cn.byteforce.seedj.starter.im.protocol.body.ConversationListReq;
import cn.byteforce.seedj.starter.im.protocol.body.ConversationListResp;
import cn.byteforce.seedj.starter.im.protocol.body.EditNotify;
import cn.byteforce.seedj.starter.im.protocol.body.EditReq;
import cn.byteforce.seedj.starter.im.protocol.body.LoginReq;
import cn.byteforce.seedj.starter.im.protocol.body.MemberListReq;
import cn.byteforce.seedj.starter.im.protocol.body.MemberListResp;
import cn.byteforce.seedj.starter.im.protocol.body.OfflinePullReq;
import cn.byteforce.seedj.starter.im.protocol.body.PresenceReq;
import cn.byteforce.seedj.starter.im.protocol.body.PresenceResp;
import cn.byteforce.seedj.starter.im.protocol.body.RecallNotify;
import cn.byteforce.seedj.starter.im.protocol.body.RecallReq;
import cn.byteforce.seedj.starter.im.protocol.body.SessionSyncReq;
import cn.byteforce.seedj.starter.im.protocol.body.TypingNotify;
import cn.byteforce.seedj.starter.im.protocol.body.TypingReq;
import cn.byteforce.seedj.starter.security.JwtClaimsMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** P4 增强：撤回、编辑、会话列表、成员列表、输入中、在线状态。 */
class ImEnhancementFlowTest {

    private static final SecretKey KEY =
            new SecretKeySpec("seedj-im-test-key-0123456789abcdef".getBytes(UTF_8), "HmacSHA256");

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-01T10:00:00Z"), ZoneOffset.UTC);

    private final ImCodec codec = new ImCodec();
    private final ChannelRegistry registry = new ChannelRegistry();
    private final ImSequenceAllocator numbers = new AtomicLongSequenceAllocator();
    private final ImConversationStore conversations =
            new InMemoryImConversationStore(numbers, CLOCK);
    private final ImMessageStore messages = new InMemoryImMessageStore(CLOCK);
    private final ImCursorStore cursors = new InMemoryImCursorStore();
    private final ImConversationPolicy policy = new ImConversationPolicy() {};
    private final ImConversationService conversationService =
            new ImConversationService(conversations, (caller, peer) -> true, policy, CLOCK);
    private final ImPushProvider push = new LocalPushProvider(codec, registry);
    private final ImMessagingService messaging =
            new ImMessagingService(
                    conversationService,
                    conversations,
                    messages,
                    cursors,
                    numbers,
                    push,
                    policy,
                    TransactionRunner.direct(),
                    OutboxWriter.discard(),
                    ImProperties.defaults(),
                    new InMemoryImCardActionStore(),
                    new ImCardActionRegistry(List.of()));
    private final ImPresenceService presence = new ImPresenceService(registry, conversations, push);
    private final ImTypingService typing =
            new ImTypingService(conversationService, conversations, push, CLOCK);
    private final ImCommandRegistry commands =
            new ImCommandRegistry(
                    List.of(
                            new ImBuiltInHandlers.Send(messaging),
                            new ImBuiltInHandlers.Ack(messaging),
                            new ImBuiltInHandlers.Read(messaging),
                            new ImBuiltInHandlers.Pull(messaging),
                            new ImBuiltInHandlers.Sync(messaging),
                            new ImBuiltInHandlers.Recall(messaging),
                            new ImBuiltInHandlers.Edit(messaging),
                            new ImBuiltInHandlers.Conversations(conversationService, messaging),
                            new ImBuiltInHandlers.Members(
                                    conversationService, subject -> "n-" + subject),
                            new ImBuiltInHandlers.Typing(typing),
                            new ImBuiltInHandlers.Presence(presence)));
    private final ImJwtAuthenticator authenticator =
            new ImJwtAuthenticator(
                    NimbusJwtDecoder.withSecretKey(KEY).macAlgorithm(MacAlgorithm.HS256).build(),
                    new JwtClaimsMapper());

    @Test
    @DisplayName("撤回与编辑：响应 + 通知、非发送者被拒、已撤回不可编辑")
    void recallAndEditRoundTrip() {
        EmbeddedChannel alice = channel(true);
        EmbeddedChannel bob = channel(true);
        try {
            login(alice, "u-1", "dev-a");
            login(bob, "u-2", "dev-b");
            ChatSendResp sent = send(alice, "c-1", "u-2", "TEXT", Map.of("text", "hi"));
            assertThat(read(bob).cmd()).isEqualTo(Cmd.CHAT_DELIVER);

            alice.writeInbound(
                    frame(
                            Cmd.RECALL_REQ,
                            new RecallReq(sent.conversationId(), sent.messageId()),
                            10));
            ImFrame recalledNotice = read(alice);
            assertThat(recalledNotice.cmd()).isEqualTo(Cmd.RECALL_REQ);
            assertThat(recalledNotice.msgType()).isEqualTo((byte) 2);
            assertThat(codec.decode(recalledNotice, RecallNotify.class).actorId()).isEqualTo("u-1");
            assertThat(read(alice).cmd()).isEqualTo(Cmd.RECALL_RESP);
            assertThat(read(bob).cmd()).isEqualTo(Cmd.RECALL_REQ);

            bob.writeInbound(
                    frame(
                            Cmd.EDIT_REQ,
                            new EditReq(
                                    sent.conversationId(), sent.messageId(), Map.of("text", "x")),
                            11));
            assertThat(read(bob).cmd()).isEqualTo(Cmd.ERROR);

            alice.writeInbound(
                    frame(
                            Cmd.EDIT_REQ,
                            new EditReq(
                                    sent.conversationId(), sent.messageId(), Map.of("text", "x")),
                            12));
            assertThat(read(alice).cmd()).isEqualTo(Cmd.ERROR);

            ChatSendResp second = send(alice, "c-2", "u-2", "TEXT", Map.of("text", "again"));
            assertThat(read(bob).cmd()).isEqualTo(Cmd.CHAT_DELIVER);
            alice.writeInbound(
                    frame(
                            Cmd.EDIT_REQ,
                            new EditReq(
                                    second.conversationId(),
                                    second.messageId(),
                                    Map.of("text", "改过")),
                            13));
            ImFrame editNotice = read(alice);
            assertThat(editNotice.cmd()).isEqualTo(Cmd.EDIT_REQ);
            assertThat(codec.decode(editNotice, EditNotify.class).body())
                    .containsEntry("text", "改过");
            assertThat(read(alice).cmd()).isEqualTo(Cmd.EDIT_RESP);
            assertThat(read(bob).cmd()).isEqualTo(Cmd.EDIT_REQ);

            bob.writeInbound(frame(Cmd.OFFLINE_PULL_REQ, new OfflinePullReq(0L, 10), 14));
            List<ChatDeliver> pulled =
                    codec.decode(
                                    read(bob),
                                    cn.byteforce.seedj.starter.im.protocol.body.OfflinePullResp
                                            .class)
                            .messages();
            assertThat(pulled).hasSize(2);
            assertThat(pulled.get(0).body()).isEmpty();
            assertThat(pulled.get(0).recalledAt()).isNotNull();
            assertThat(pulled.get(1).body()).containsEntry("text", "改过");
            assertThat(pulled.get(1).editedAt()).isNotNull();

            bob.writeInbound(frame(Cmd.ACK_REQ, new AckReq(sent.conversationId(), 1), 15));
            bob.writeInbound(frame(Cmd.SESSION_SYNC_REQ, new SessionSyncReq(0L), 16));
            assertThat(read(bob).cmd()).isEqualTo(Cmd.SESSION_SYNC_RESP);
        } finally {
            alice.finishAndReleaseAll();
            bob.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("会话列表、成员列表、输入中与在线状态")
    void listingTypingAndPresence() {
        EmbeddedChannel alice = channel(true);
        EmbeddedChannel bob = channel(true);
        try {
            login(alice, "u-1", "dev-a");
            login(bob, "u-2", "dev-b");
            ChatSendResp sent = send(alice, "c-1", "u-2", "TEXT", Map.of("text", "hi"));
            assertThat(read(bob).cmd()).isEqualTo(Cmd.CHAT_DELIVER);

            alice.writeInbound(
                    frame(Cmd.CONVERSATION_LIST_REQ, new ConversationListReq(1, 20), 20));
            ConversationListResp list = codec.decode(read(alice), ConversationListResp.class);
            assertThat(list.total()).isEqualTo(1);
            assertThat(list.conversations()).hasSize(1);
            assertThat(list.conversations().get(0).conversationId())
                    .isEqualTo(sent.conversationId());

            alice.writeInbound(
                    frame(Cmd.MEMBER_LIST_REQ, new MemberListReq(sent.conversationId(), null), 21));
            MemberListResp members = codec.decode(read(alice), MemberListResp.class);
            assertThat(members.members())
                    .extracting(MemberListResp.MemberView::subjectId)
                    .containsExactly("u-1", "u-2");
            assertThat(members.members().get(1).displayName()).isEqualTo("n-u-2");

            alice.writeInbound(
                    frame(Cmd.MEMBER_LIST_REQ, new MemberListReq(sent.conversationId(), 0L), 22));
            assertThat(read(alice).cmd()).isEqualTo(Cmd.ERROR);

            bob.writeInbound(frame(Cmd.TYPING, new TypingReq(sent.conversationId(), true), 23));
            TypingNotify notice = codec.decode(read(alice), TypingNotify.class);
            assertThat(notice.subjectId()).isEqualTo("u-2");
            assertThat(notice.typing()).isTrue();
            assertThat(read(bob)).isNull();

            bob.writeInbound(frame(Cmd.TYPING, new TypingReq(sent.conversationId(), true), 24));
            assertThat(read(alice)).isNull();

            alice.writeInbound(frame(Cmd.PRESENCE_REQ, new PresenceReq(List.of("u-2", "u-9")), 25));
            PresenceResp presenceResp = codec.decode(read(alice), PresenceResp.class);
            assertThat(presenceResp.entries()).hasSize(1);
            assertThat(presenceResp.entries().get(0).subjectId()).isEqualTo("u-2");
            assertThat(presenceResp.entries().get(0).online()).isTrue();
            assertThat(presenceResp.entries().get(0).devices()).isEqualTo(1);

            bob.pipeline().fireChannelInactive();
            PresenceResp.Entry offline = codec.decode(read(alice), PresenceResp.Entry.class);
            assertThat(offline.subjectId()).isEqualTo("u-2");
            assertThat(offline.online()).isFalse();
        } finally {
            alice.finishAndReleaseAll();
            bob.finishAndReleaseAll();
        }
    }

    private EmbeddedChannel channel(boolean withPresence) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("presence", presence);
        return new EmbeddedChannel(
                new ImHandshakeHandler(
                        codec,
                        authenticator,
                        registry,
                        ImProperties.defaults(),
                        withPresence ? beans.getBeanProvider(ImPresenceListener.class) : null,
                        null),
                new ImDispatchHandler(codec, commands));
    }

    private ChatSendResp send(
            EmbeddedChannel channel,
            String clientId,
            String peer,
            String kind,
            Map<String, Object> body) {
        channel.writeInbound(
                frame(Cmd.CHAT_SINGLE_REQ, new ChatSendReq(clientId, null, peer, kind, body), 2));
        return codec.decode(read(channel), ChatSendResp.class);
    }

    private void login(EmbeddedChannel channel, String subject, String deviceId) {
        channel.writeInbound(frame(Cmd.LOGIN_REQ, new LoginReq(token(subject), deviceId), 1));
        assertThat(read(channel).cmd()).isEqualTo(Cmd.LOGIN_RESP);
    }

    private static ImFrame read(EmbeddedChannel channel) {
        return channel.readOutbound();
    }

    private ImFrame frame(byte cmd, Object body, int sequenceId) {
        return codec.resp(cmd, sequenceId, body);
    }

    private static String token(String userId) {
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(KEY));
        Instant now = Instant.now();
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .subject(userId)
                        .claim("user_id", userId)
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
