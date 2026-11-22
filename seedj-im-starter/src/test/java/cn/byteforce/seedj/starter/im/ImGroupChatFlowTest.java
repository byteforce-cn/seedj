package cn.byteforce.seedj.starter.im;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.infra.local.im.InMemoryImCardActionStore;
import cn.byteforce.seedj.im.ImConversation;
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
import cn.byteforce.seedj.starter.im.protocol.ImCodec;
import cn.byteforce.seedj.starter.im.protocol.ImFrame;
import cn.byteforce.seedj.starter.im.protocol.body.ChatDeliver;
import cn.byteforce.seedj.starter.im.protocol.body.ChatSendReq;
import cn.byteforce.seedj.starter.im.protocol.body.ChatSendResp;
import cn.byteforce.seedj.starter.im.protocol.body.ErrorResp;
import cn.byteforce.seedj.starter.im.protocol.body.LoginReq;
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
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** 群聊端到端：建群 → 群内广播 → 命令字与响应命令字不串。 */
class ImGroupChatFlowTest {

    private static final SecretKey KEY =
            new SecretKeySpec("seedj-im-test-key-0123456789abcdef".getBytes(UTF_8), "HmacSHA256");

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneOffset.UTC);

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
    private final ImMessagingService messaging =
            new ImMessagingService(
                    conversationService,
                    conversations,
                    messages,
                    cursors,
                    numbers,
                    new LocalPushProvider(codec, registry),
                    policy,
                    TransactionRunner.direct(),
                    OutboxWriter.discard(),
                    ImProperties.defaults(),
                    new InMemoryImCardActionStore(),
                    new ImCardActionRegistry(List.of()));
    private final ImCommandRegistry commands =
            new ImCommandRegistry(
                    List.of(
                            new ImBuiltInHandlers.Send(messaging),
                            new ImBuiltInHandlers.SendGroup(messaging)));
    private final ImJwtAuthenticator authenticator =
            new ImJwtAuthenticator(
                    NimbusJwtDecoder.withSecretKey(KEY).macAlgorithm(MacAlgorithm.HS256).build(),
                    new JwtClaimsMapper());

    @Test
    @DisplayName("群内发送广播给其它成员，响应走 CHAT_GROUP_RESP")
    void groupSendFansOutToMembers() {
        EmbeddedChannel alice = channel();
        EmbeddedChannel bob = channel();
        EmbeddedChannel carol = channel();
        try {
            login(alice, "u-1");
            login(bob, "u-2");
            login(carol, "u-3");
            ImConversation group =
                    conversationService.createGroup(caller("u-1"), "研发", List.of("u-2", "u-3"));

            alice.writeInbound(
                    frame(
                            Cmd.CHAT_GROUP_REQ,
                            new ChatSendReq(
                                    "c-1",
                                    group.id().toString(),
                                    null,
                                    "TEXT",
                                    Map.of("text", "站会 10 点")),
                            2));

            ImFrame response = read(alice);
            assertThat(response.cmd()).isEqualTo(Cmd.CHAT_GROUP_RESP);
            ChatSendResp sent = codec.decode(response, ChatSendResp.class);
            assertThat(sent.conversationId()).isEqualTo(group.id().toString());

            ChatDeliver forBob = codec.decode(read(bob), ChatDeliver.class);
            ChatDeliver forCarol = codec.decode(read(carol), ChatDeliver.class);
            assertThat(forBob.messageId()).isEqualTo(sent.messageId());
            assertThat(forCarol.messageId()).isEqualTo(sent.messageId());
            assertThat(read(alice)).isNull();
        } finally {
            alice.finishAndReleaseAll();
            bob.finishAndReleaseAll();
            carol.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("群聊命令不接受单聊会话，单聊命令不接受群会话缺省")
    void commandKindsAreStrict() {
        EmbeddedChannel alice = channel();
        try {
            login(alice, "u-1");
            ImConversation single = conversationService.createSingle(caller("u-1"), "u-2");

            alice.writeInbound(
                    frame(
                            Cmd.CHAT_GROUP_REQ,
                            new ChatSendReq("c-1", single.id().toString(), null, "TEXT", Map.of()),
                            2));
            assertThat(errorCode(read(alice))).isEqualTo(400);

            alice.writeInbound(
                    frame(
                            Cmd.CHAT_GROUP_REQ,
                            new ChatSendReq("c-2", null, "u-2", "TEXT", Map.of()),
                            3));
            assertThat(errorCode(read(alice))).isEqualTo(400);
            assertThat(alice.isActive()).isTrue();
        } finally {
            alice.finishAndReleaseAll();
        }
    }

    private static int errorCode(ImFrame frame) {
        return new ImCodec().decode(frame, ErrorResp.class).code();
    }

    private EmbeddedChannel channel() {
        return new EmbeddedChannel(
                new ImHandshakeHandler(codec, authenticator, registry),
                new ImDispatchHandler(codec, commands));
    }

    private void login(EmbeddedChannel channel, String userId) {
        channel.writeInbound(frame(Cmd.LOGIN_REQ, new LoginReq(token(userId), "dev-" + userId), 1));
        assertThat(read(channel).cmd()).isEqualTo(Cmd.LOGIN_RESP);
    }

    private static ImFrame read(EmbeddedChannel channel) {
        return channel.readOutbound();
    }

    private ImFrame frame(byte cmd, Object body, int sequenceId) {
        return codec.resp(cmd, sequenceId, body);
    }

    private static cn.byteforce.seedj.core.security.Caller caller(String id) {
        return new cn.byteforce.seedj.core.security.Caller(
                id,
                id,
                cn.byteforce.seedj.core.security.Caller.DEFAULT_TENANT,
                null,
                null,
                null,
                null,
                null,
                null);
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
