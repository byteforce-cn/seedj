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
import cn.byteforce.seedj.starter.im.protocol.body.LoginReq;
import cn.byteforce.seedj.starter.im.protocol.body.OfflinePullReq;
import cn.byteforce.seedj.starter.im.protocol.body.OfflinePullResp;
import cn.byteforce.seedj.starter.im.protocol.body.ReadReceiptReq;
import cn.byteforce.seedj.starter.im.protocol.body.SessionSyncReq;
import cn.byteforce.seedj.starter.im.protocol.body.SessionSyncResp;
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

/** 单聊端到端：发送 → 投递 → 已读 → 补拉 → 同步。 */
class ImSingleChatFlowTest {

    private static final SecretKey KEY =
            new SecretKeySpec("seedj-im-test-key-0123456789abcdef".getBytes(UTF_8), "HmacSHA256");

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-05-01T10:00:00Z"), ZoneOffset.UTC);

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
                            new ImBuiltInHandlers.Ack(messaging),
                            new ImBuiltInHandlers.Read(messaging),
                            new ImBuiltInHandlers.Pull(messaging),
                            new ImBuiltInHandlers.Sync(messaging)));
    private final ImJwtAuthenticator authenticator =
            new ImJwtAuthenticator(
                    NimbusJwtDecoder.withSecretKey(KEY).macAlgorithm(MacAlgorithm.HS256).build(),
                    new JwtClaimsMapper());

    @Test
    @DisplayName("A 发送后 B 收到投递；B 已读后 A 收到回执；B 可补拉与同步")
    void singleChatRoundTrip() {
        EmbeddedChannel alice = channel();
        EmbeddedChannel bob = channel();
        try {
            login(alice, "u-1", "dev-a");
            login(bob, "u-2", "dev-b");

            alice.writeInbound(
                    frame(
                            Cmd.CHAT_SINGLE_REQ,
                            new ChatSendReq("c-1", null, "u-2", "TEXT", Map.of("text", "你好")),
                            2));
            ImFrame ack = read(alice);
            ChatSendResp sent = codec.decode(ack, ChatSendResp.class);
            assertThat(ack.cmd()).isEqualTo(Cmd.CHAT_SINGLE_RESP);
            assertThat(sent.seq()).isPositive();
            assertThat(sent.conversationId()).isNotBlank();

            ImFrame deliveredFrame = read(bob);
            assertThat(deliveredFrame.cmd()).isEqualTo(Cmd.CHAT_DELIVER);
            ChatDeliver delivered = codec.decode(deliveredFrame, ChatDeliver.class);
            assertThat(delivered.messageId()).isEqualTo(sent.messageId());
            assertThat(delivered.body()).containsEntry("text", "你好");
            assertThat(read(alice)).isNull();

            bob.writeInbound(
                    frame(
                            Cmd.READ_RECEIPT,
                            new ReadReceiptReq(sent.conversationId(), sent.seq()),
                            3));
            assertThat(read(bob)).isNull();
            ImFrame receipt = read(alice);
            assertThat(receipt.cmd()).isEqualTo(Cmd.READ_RECEIPT);
            assertThat(receipt.sequenceId()).isZero();
            assertThat(codec.decode(receipt, ReadReceiptReq.class).seq()).isEqualTo(sent.seq());

            bob.writeInbound(frame(Cmd.ACK_REQ, new AckReq(sent.conversationId(), sent.seq()), 4));
            assertThat(read(bob)).isNull();

            bob.writeInbound(frame(Cmd.OFFLINE_PULL_REQ, new OfflinePullReq(0L, 10), 5));
            ImFrame pulledFrame = read(bob);
            assertThat(pulledFrame.cmd()).isEqualTo(Cmd.OFFLINE_PULL_RESP);
            OfflinePullResp pulled = codec.decode(pulledFrame, OfflinePullResp.class);
            assertThat(pulled.messages()).hasSize(1);
            assertThat(pulled.messages().get(0).messageId()).isEqualTo(sent.messageId());

            bob.writeInbound(frame(Cmd.SESSION_SYNC_REQ, new SessionSyncReq(0L), 6));
            ImFrame syncFrame = read(bob);
            assertThat(syncFrame.cmd()).isEqualTo(Cmd.SESSION_SYNC_RESP);
            SessionSyncResp sync = codec.decode(syncFrame, SessionSyncResp.class);
            assertThat(sync.conversations()).hasSize(1);
            assertThat(sync.nextCursor()).isEqualTo(sent.seq());
        } finally {
            alice.finishAndReleaseAll();
            bob.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("重复 client_msg_id 只产生一条消息")
    void duplicateSendIsIdempotent() {
        EmbeddedChannel alice = channel();
        EmbeddedChannel bob = channel();
        try {
            login(alice, "u-1", "dev-a");
            login(bob, "u-2", "dev-b");
            ChatSendReq request = new ChatSendReq("c-1", null, "u-2", "TEXT", Map.of("n", 1));

            alice.writeInbound(frame(Cmd.CHAT_SINGLE_REQ, request, 2));
            ChatSendResp first = codec.decode(read(alice), ChatSendResp.class);
            assertThat(read(bob)).isNotNull();

            alice.writeInbound(frame(Cmd.CHAT_SINGLE_REQ, request, 3));
            ChatSendResp replay = codec.decode(read(alice), ChatSendResp.class);

            assertThat(replay.messageId()).isEqualTo(first.messageId());
            assertThat(replay.seq()).isEqualTo(first.seq());
            assertThat(read(bob)).isNull();
        } finally {
            alice.finishAndReleaseAll();
            bob.finishAndReleaseAll();
        }
    }

    private EmbeddedChannel channel() {
        return new EmbeddedChannel(
                new ImHandshakeHandler(codec, authenticator, registry),
                new ImDispatchHandler(codec, commands));
    }

    private void login(EmbeddedChannel channel, String subject, String deviceId) {
        channel.writeInbound(frame(Cmd.LOGIN_REQ, new LoginReq(token(subject), deviceId), 1));
        assertThat(channel.<ImFrame>readOutbound().cmd()).isEqualTo(Cmd.LOGIN_RESP);
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
