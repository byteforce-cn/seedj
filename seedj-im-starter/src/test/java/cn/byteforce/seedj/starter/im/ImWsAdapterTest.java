package cn.byteforce.seedj.starter.im;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.starter.im.protocol.Cmd;
import cn.byteforce.seedj.starter.im.protocol.ImFrame;
import cn.byteforce.seedj.starter.im.protocol.ImFrames;
import cn.byteforce.seedj.starter.im.protocol.body.LoginReq;
import cn.byteforce.seedj.starter.im.protocol.body.LoginResp;
import cn.byteforce.seedj.starter.security.JwtClaimsMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** WebSocket 二进制帧与 {@link ImFrame} 的互转。 */
class ImWsAdapterTest {

    private static final SecretKey KEY =
            new SecretKeySpec("seedj-im-ws-test-key-0123456789abc".getBytes(UTF_8), "HmacSHA256");
    private static final ImCodec CODEC = new ImCodec();

    @Test
    @DisplayName("BinaryWebSocketFrame 解码为 ImFrame")
    void binaryFrameBecomesImFrame() {
        EmbeddedChannel channel = new EmbeddedChannel(new ImWsAdapter());
        try {
            ImFrame source = frame();
            ByteBuf content = Unpooled.buffer();
            ImFrames.write(content, source);

            assertThat(channel.writeInbound(new BinaryWebSocketFrame(content))).isTrue();
            ImFrame decoded = channel.readInbound();

            assertThat(decoded).isNotNull();
            assertThat(decoded.cmd()).isEqualTo(Cmd.LOGIN_REQ);
            assertThat(decoded.sequenceId()).isEqualTo(3);
            assertThat(new String(decoded.body(), UTF_8)).isEqualTo("{\"access_token\":\"t\"}");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("ImFrame 编码为 BinaryWebSocketFrame")
    void imFrameBecomesBinaryFrame() {
        EmbeddedChannel channel = new EmbeddedChannel(new ImWsAdapter());
        try {
            channel.writeOutbound(frame());

            BinaryWebSocketFrame outbound = channel.readOutbound();
            assertThat(outbound).isNotNull();
            ImFrame decoded = ImFrames.read(outbound.content());
            assertThat(decoded.magic()).isEqualTo(ImFrames.MAGIC);
            assertThat(decoded.sequenceId()).isEqualTo(3);
            outbound.release();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("非法负载关闭连接")
    void garbagePayloadClosesChannel() {
        EmbeddedChannel channel = new EmbeddedChannel(new ImWsAdapter());
        try {
            channel.writeInbound(
                    new BinaryWebSocketFrame(Unpooled.wrappedBuffer(new byte[] {1, 2, 3})));

            assertThat(channel.isActive()).isFalse();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("WebSocket 服务绑定 0 端口，stop 后端口释放")
    void wsServerStartsAndReleasesPort() throws IOException {
        ImProperties properties = properties();
        ImWsServer server = new ImWsServer(properties, new ImWsChannelInitializer(properties));
        try {
            server.start();

            assertThat(server.boundPort()).isPositive();
        } finally {
            int port = server.boundPort();
            server.stop();
            try (ServerSocket rebound = new ServerSocket(port)) {
                assertThat(rebound.getLocalPort()).isEqualTo(port);
            }
        }
    }

    @Test
    @DisplayName("WebSocket 全链路：升级、登录、PONG")
    void websocketPipelineHandlesLoginAndHeartbeat() throws Exception {
        ImProperties properties = properties();
        ChannelRegistry registry = new ChannelRegistry();
        ImJwtAuthenticator authenticator = new ImJwtAuthenticator(decoder(), new JwtClaimsMapper());
        ImWsServer server =
                new ImWsServer(
                        properties,
                        new ImWsChannelInitializer(
                                properties,
                                new ImHandshakeHandler(CODEC, authenticator, registry),
                                new ImDispatchHandler(CODEC)));
        BlockingQueue<ImFrame> received = new LinkedBlockingQueue<>();
        server.start();
        WebSocket socket = null;
        try {
            socket =
                    HttpClient.newHttpClient()
                            .newWebSocketBuilder()
                            .buildAsync(
                                    URI.create("ws://127.0.0.1:" + server.boundPort() + "/ws"),
                                    new WebSocket.Listener() {
                                        @Override
                                        public CompletionStage<?> onBinary(
                                                WebSocket webSocket,
                                                ByteBuffer data,
                                                boolean last) {
                                            byte[] bytes = new byte[data.remaining()];
                                            data.get(bytes);
                                            received.add(
                                                    ImFrames.read(Unpooled.wrappedBuffer(bytes)));
                                            webSocket.request(1);
                                            return null;
                                        }
                                    })
                            .join();

            socket.sendBinary(
                            ByteBuffer.wrap(
                                    frameBytes(Cmd.LOGIN_REQ, 1, new LoginReq(validToken()))),
                            true)
                    .join();
            ImFrame login = received.poll(5, TimeUnit.SECONDS);
            assertThat(login).isNotNull();
            assertThat(login.cmd()).isEqualTo(Cmd.LOGIN_RESP);
            assertThat(CODEC.decode(login, LoginResp.class).code()).isZero();
            assertThat(registry.size()).isEqualTo(1);

            socket.sendBinary(ByteBuffer.wrap(frameBytes(Cmd.HEARTBEAT_PING, 2, null)), true)
                    .join();
            ImFrame pong = received.poll(5, TimeUnit.SECONDS);
            assertThat(pong).isNotNull();
            assertThat(pong.cmd()).isEqualTo(Cmd.HEARTBEAT_PONG);
        } finally {
            if (socket != null) {
                socket.abort();
            }
            server.stop();
        }
    }

    private static byte[] frameBytes(byte cmd, int sequenceId, Object body) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            ImFrames.write(buffer, CODEC.resp(cmd, sequenceId, body));
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static JwtDecoder decoder() {
        return NimbusJwtDecoder.withSecretKey(KEY).macAlgorithm(MacAlgorithm.HS256).build();
    }

    private static String validToken() {
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(KEY));
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .subject("alice")
                        .claim("user_id", "u-1")
                        .claim("permissions", List.of("im.message.send"))
                        .issuedAt(Instant.now().minusSeconds(5))
                        .expiresAt(Instant.now().plusSeconds(300))
                        .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static ImFrame frame() {
        return new ImFrame(
                ImFrames.MAGIC,
                (byte) 1,
                (byte) 1,
                Cmd.LOGIN_REQ,
                (byte) 0,
                3,
                "{\"access_token\":\"t\"}".getBytes(UTF_8));
    }

    private static ImProperties properties() {
        return new ImProperties(
                true,
                new ImProperties.Push("local"),
                new ImProperties.Tcp(false, 0),
                new ImProperties.Ws(true, 0, "/ws"),
                Duration.ofSeconds(90),
                1024,
                1000);
    }
}
