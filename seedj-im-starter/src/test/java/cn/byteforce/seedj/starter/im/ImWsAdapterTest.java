package cn.byteforce.seedj.starter.im;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.starter.im.protocol.Cmd;
import cn.byteforce.seedj.starter.im.protocol.ImFrame;
import cn.byteforce.seedj.starter.im.protocol.ImFrames;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** WebSocket 二进制帧与 {@link ImFrame} 的互转。 */
class ImWsAdapterTest {

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
