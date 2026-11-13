package cn.byteforce.seedj.starter.im;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.starter.im.protocol.ImFrames;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TCP 传输的生命周期、端口释放与超大帧拒绝。 */
class ImTcpServerTest {

    @Test
    @DisplayName("绑定 0 端口后暴露实际端口")
    void startsAndExposesBoundPort() {
        ImProperties properties = properties(1024);
        ImTcpServer server = server(properties);
        try {
            server.start();

            assertThat(server.boundPort()).isPositive();
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("stop 后端口立即可再次绑定")
    void stopReleasesPort() throws IOException {
        ImProperties properties = properties(1024);
        ImTcpServer server = server(properties);
        server.start();
        int port = server.boundPort();
        server.stop();

        try (ServerSocket rebound = new ServerSocket(port)) {
            assertThat(rebound.getLocalPort()).isEqualTo(port);
        }
    }

    @Test
    @DisplayName("声明超过 maxFrameLength 的 body 时服务端关闭连接")
    void oversizedFrameClosesConnection() throws IOException {
        ImProperties properties = properties(64);
        ImTcpServer server = server(properties);
        try {
            server.start();
            try (Socket socket = new Socket("127.0.0.1", server.boundPort())) {
                socket.setSoTimeout(3000);
                OutputStream out = socket.getOutputStream();
                out.write(oversizedHeader(64 + 1));
                out.flush();

                InputStream in = socket.getInputStream();
                int value;
                try {
                    value = in.read();
                } catch (IOException closed) {
                    return;
                }
                assertThat(value).as("服务端应关闭连接而不是回正常响应").isEqualTo(-1);
            }
        } finally {
            server.stop();
        }
    }

    private static ImTcpServer server(ImProperties properties) {
        return new ImTcpServer(properties, new ImChannelInitializer(properties));
    }

    private static ImProperties properties(int maxFrameLength) {
        return new ImProperties(
                true,
                new ImProperties.Push("local"),
                new ImProperties.Tcp(true, 0),
                new ImProperties.Ws(false, 0, "/ws"),
                Duration.ofSeconds(90),
                maxFrameLength,
                1000);
    }

    private static byte[] oversizedHeader(int bodyLength) {
        byte[] header = new byte[ImFrames.HEADER_LENGTH];
        header[0] = (byte) 0xCA;
        header[1] = (byte) 0xFE;
        header[2] = (byte) 0xBA;
        header[3] = (byte) 0xBE;
        header[4] = 1;
        header[5] = 1;
        header[6] = 1;
        header[7] = 0;
        Arrays.fill(header, 8, 12, (byte) 0);
        header[12] = (byte) (bodyLength >>> 24);
        header[13] = (byte) (bodyLength >>> 16);
        header[14] = (byte) (bodyLength >>> 8);
        header[15] = (byte) bodyLength;
        return header;
    }
}
