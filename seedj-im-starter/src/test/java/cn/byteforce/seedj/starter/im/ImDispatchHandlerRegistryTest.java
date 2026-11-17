package cn.byteforce.seedj.starter.im;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.starter.im.protocol.Cmd;
import cn.byteforce.seedj.starter.im.protocol.ImFrame;
import cn.byteforce.seedj.starter.im.protocol.body.LoginReq;
import cn.byteforce.seedj.starter.security.JwtClaimsMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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
import tools.jackson.databind.json.JsonMapper;

/** 注册表驱动的分发：回包、通知、异常映射与空响应。 */
class ImDispatchHandlerRegistryTest {

    private static final SecretKey KEY =
            new SecretKeySpec("seedj-im-test-key-0123456789abcdef".getBytes(UTF_8), "HmacSHA256");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ImCodec codec = new ImCodec();
    private final ChannelRegistry registry = new ChannelRegistry();
    private final ImJwtAuthenticator authenticator =
            new ImJwtAuthenticator(
                    NimbusJwtDecoder.withSecretKey(KEY).macAlgorithm(MacAlgorithm.HS256).build(),
                    new JwtClaimsMapper());

    @Test
    @DisplayName("已注册命令字按上下文回包，序号原样带回")
    void registeredHandlerRepliesWithSameSequence() {
        AtomicReference<ImCallContext> seen = new AtomicReference<>();
        ImCommandHandler<Map<String, Object>> handler =
                fixedHandler(
                        Cmd.CHAT_SINGLE_REQ,
                        mapType(),
                        (context, body) -> {
                            seen.set(context);
                            return ImReply.of(Cmd.CHAT_SINGLE_RESP, Map.of("seq", 42));
                        });

        EmbeddedChannel channel = channel(handler);
        try {
            login(channel);
            channel.writeInbound(frame(Cmd.CHAT_SINGLE_REQ, Map.of("text", "hi"), 21));

            ImFrame response = channel.readOutbound();
            assertThat(response.cmd()).isEqualTo(Cmd.CHAT_SINGLE_RESP);
            assertThat(response.sequenceId()).isEqualTo(21);
            assertThat(JSON.readValue(response.body(), Map.class)).containsEntry("seq", 42);
            assertThat(seen.get().caller().id()).isEqualTo("u-1");
            assertThat(seen.get().deviceId()).isEqualTo("dev-1");
            assertThat(seen.get().sequenceId()).isEqualTo(21);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("处理器主动通知走 msgType=2 且序号为 0")
    void handlerCanNotify() {
        ImCommandHandler<Map<String, Object>> handler =
                fixedHandler(
                        Cmd.CHAT_GROUP_REQ,
                        mapType(),
                        (context, body) -> {
                            context.notify(Cmd.READ_RECEIPT, Map.of("seq", 7));
                            return null;
                        });

        EmbeddedChannel channel = channel(handler);
        try {
            login(channel);
            channel.writeInbound(frame(Cmd.CHAT_GROUP_REQ, Map.of(), 22));

            ImFrame notification = channel.readOutbound();
            assertThat(notification.cmd()).isEqualTo(Cmd.READ_RECEIPT);
            assertThat(notification.sequenceId()).isZero();
            assertThat(notification.msgType()).isEqualTo((byte) 2);
            assertThat(channel.<ImFrame>readOutbound()).isNull();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("契约异常映射成协议错误码，连接保持")
    void contractExceptionMapsToProtocolError() {
        ImCommandHandler<Map<String, Object>> handler =
                fixedHandler(
                        Cmd.ACK_REQ,
                        mapType(),
                        (context, body) -> {
                            throw new ContractException(ContractErrorCode.FORBIDDEN, "无权限");
                        });

        EmbeddedChannel channel = channel(handler);
        try {
            login(channel);
            channel.writeInbound(frame(Cmd.ACK_REQ, Map.of(), 23));

            ImFrame response = channel.readOutbound();
            assertThat(response.cmd()).isEqualTo(Cmd.ERROR);
            assertThat(JSON.readValue(response.body(), Map.class))
                    .containsEntry("code", 403)
                    .containsEntry("message", "无权限");
            assertThat(channel.isActive()).isTrue();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("其它异常统一 500，细节不外泄")
    void unexpectedExceptionMapsTo500() {
        ImCommandHandler<Map<String, Object>> handler =
                fixedHandler(
                        Cmd.OFFLINE_PULL_REQ,
                        mapType(),
                        (context, body) -> {
                            throw new IllegalStateException("boom");
                        });

        EmbeddedChannel channel = channel(handler);
        try {
            login(channel);
            channel.writeInbound(frame(Cmd.OFFLINE_PULL_REQ, Map.of(), 24));

            ImFrame response = channel.readOutbound();
            assertThat(response.cmd()).isEqualTo(Cmd.ERROR);
            assertThat(JSON.readValue(response.body(), Map.class))
                    .containsEntry("code", 500)
                    .containsEntry("message", "内部错误");
            assertThat(channel.isActive()).isTrue();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("空 body 的请求把 null 交给处理器")
    void emptyBodyIsDecodedToNull() {
        AtomicReference<Object> seen = new AtomicReference<>("unset");
        ImCommandHandler<Map<String, Object>> handler =
                fixedHandler(
                        Cmd.ACK_REQ,
                        mapType(),
                        (context, body) -> {
                            seen.set(body);
                            return ImReply.of(Cmd.ERROR, Map.of("code", 0));
                        });

        EmbeddedChannel channel = channel(handler);
        try {
            login(channel);
            channel.writeInbound(frame(Cmd.ACK_REQ, null, 25));

            ImFrame acked = channel.readOutbound();
            assertThat(acked).isNotNull();
            assertThat(seen).hasValue(null);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private EmbeddedChannel channel(ImCommandHandler<?> handler) {
        return new EmbeddedChannel(
                new ImHandshakeHandler(codec, authenticator, registry),
                new ImDispatchHandler(codec, new ImCommandRegistry(List.of(handler))));
    }

    private void login(EmbeddedChannel channel) {
        channel.writeInbound(frame(Cmd.LOGIN_REQ, new LoginReq(token(), "dev-1"), 1));
        assertThat(channel.<ImFrame>readOutbound().cmd()).isEqualTo(Cmd.LOGIN_RESP);
    }

    private ImFrame frame(byte cmd, Object body, int sequenceId) {
        return codec.resp(cmd, sequenceId, body);
    }

    private static <B> ImCommandHandler<B> fixedHandler(
            byte cmd, Class<B> bodyType, Handler<B> body) {
        return new ImCommandHandler<>() {
            @Override
            public byte cmd() {
                return cmd;
            }

            @Override
            public Class<B> bodyType() {
                return bodyType;
            }

            @Override
            public ImReply handle(ImCallContext context, B payload) {
                return body.apply(context, payload);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Class<Map<String, Object>> mapType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    @FunctionalInterface
    private interface Handler<B> {
        ImReply apply(ImCallContext context, B body);
    }

    private static String token() {
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(KEY));
        Instant now = Instant.now();
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .subject("alice")
                        .claim("user_id", "u-1")
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
