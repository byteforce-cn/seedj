package cn.byteforce.seedj.server.im;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.starter.im.protocol.Cmd;
import cn.byteforce.seedj.starter.im.protocol.ImFrame;
import cn.byteforce.seedj.starter.im.protocol.ImFrames;
import cn.byteforce.seedj.starter.im.protocol.body.ErrorResp;
import cn.byteforce.seedj.starter.im.protocol.body.LoginReq;
import cn.byteforce.seedj.starter.im.protocol.body.LoginResp;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.MessageToMessageEncoder;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import tools.jackson.databind.json.JsonMapper;

/** 宿主端到端：登录、心跳、未实现命令 501，以及停机后端口释放。 */
class ImServerE2ETest {

    private static final SecretKey KEY =
            new SecretKeySpec(
                    "seedj-im-e2e-key-0123456789abcdefgh".getBytes(UTF_8), "HmacSHA256");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int TCP_PORT = freePort();

    @Test
    @DisplayName("TCP 登录 → PONG → 未实现 501，停机后端口可重绑")
    void loginHeartbeatAndUnimplementedCommand() throws Exception {
        ConfigurableApplicationContext context =
                new SpringApplicationBuilder(ImServer.class, TestJwtConfiguration.class)
                        .profiles("local")
                        .run(
                                "--seedj.im.tcp.port=" + TCP_PORT,
                                "--seedj.im.ws.enabled=false",
                                "--server.port=0");
        try {
            try (FrameClient client = new FrameClient(TCP_PORT)) {
                client.send(frame(Cmd.LOGIN_REQ, 1, new LoginReq(validToken())));

                ImFrame login = client.next();
                assertThat(login.cmd()).isEqualTo(Cmd.LOGIN_RESP);
                LoginResp loginBody = JSON.readValue(login.body(), LoginResp.class);
                assertThat(loginBody.code()).isZero();
                assertThat(loginBody.userId()).isEqualTo("u-1");

                client.send(frame(Cmd.HEARTBEAT_PING, 2, null));
                assertThat(client.next().cmd()).isEqualTo(Cmd.HEARTBEAT_PONG);

                client.send(frame(Cmd.CHAT_SINGLE_REQ, 3, null));
                ImFrame unimplemented = client.next();
                assertThat(unimplemented.cmd()).isEqualTo(Cmd.ERROR);
                assertThat(JSON.readValue(unimplemented.body(), ErrorResp.class).code())
                        .isEqualTo(501);
            }
        } finally {
            context.close();
        }

        try (ServerSocket rebound = new ServerSocket(TCP_PORT)) {
            assertThat(rebound.getLocalPort()).isEqualTo(TCP_PORT);
        }
    }

    private static String validToken() {
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(KEY));
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .subject("alice")
                        .claim("user_id", "u-1")
                        .claim("name", "Alice")
                        .claim("permissions", List.of("im.message.send"))
                        .issuedAt(Instant.now().minusSeconds(5))
                        .expiresAt(Instant.now().plusSeconds(300))
                        .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static ImFrame frame(byte cmd, int sequenceId, Object body) {
        byte[] json = body == null ? new byte[0] : JSON.writeValueAsBytes(body);
        return new ImFrame(ImFrames.MAGIC, (byte) 1, (byte) 1, cmd, (byte) 0, sequenceId, json);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException error) {
            throw new IllegalStateException("无法分配空闲端口", error);
        }
    }

    /** 最小 TCP 客户端：完整帧进、完整帧出。 */
    private static final class FrameClient implements AutoCloseable {

        private final EventLoopGroup group = new NioEventLoopGroup(1);
        private final BlockingQueue<ImFrame> inbound = new LinkedBlockingQueue<>();
        private final Channel channel;

        FrameClient(int port) {
            channel =
                    new Bootstrap()
                            .group(group)
                            .channel(NioSocketChannel.class)
                            .handler(
                                    new ChannelInitializer<SocketChannel>() {
                                        @Override
                                        protected void initChannel(SocketChannel socket) {
                                            socket.pipeline()
                                                    .addLast(
                                                            new LengthFieldBasedFrameDecoder(
                                                                    1_048_576, 12, 4, 0, 0))
                                                    .addLast(
                                                            new MessageToMessageEncoder<ImFrame>() {
                                                                @Override
                                                                protected void encode(
                                                                        ChannelHandlerContext ctx,
                                                                        ImFrame frame,
                                                                        List<Object> out) {
                                                                    int length =
                                                                            ImFrames.HEADER_LENGTH
                                                                                    + frame.body()
                                                                                            .length;
                                                                    ByteBuf buffer =
                                                                            ctx.alloc()
                                                                                    .buffer(length);
                                                                    ImFrames.write(buffer, frame);
                                                                    out.add(buffer);
                                                                }
                                                            })
                                                    .addLast(
                                                            new SimpleChannelInboundHandler<
                                                                    ByteBuf>() {
                                                                @Override
                                                                protected void channelRead0(
                                                                        ChannelHandlerContext ctx,
                                                                        ByteBuf msg) {
                                                                    inbound.add(ImFrames.read(msg));
                                                                }
                                                            });
                                        }
                                    })
                            .connect("127.0.0.1", port)
                            .syncUninterruptibly()
                            .channel();
        }

        void send(ImFrame frame) {
            channel.writeAndFlush(frame).syncUninterruptibly();
        }

        ImFrame next() throws InterruptedException {
            ImFrame frame = inbound.poll(5, TimeUnit.SECONDS);
            assertThat(frame).as("等待服务端响应超时").isNotNull();
            return frame;
        }

        @Override
        public void close() {
            channel.close().syncUninterruptibly();
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TestJwtConfiguration {

        @Bean
        JwtDecoder jwtDecoder() {
            return NimbusJwtDecoder.withSecretKey(KEY).macAlgorithm(MacAlgorithm.HS256).build();
        }
    }
}
