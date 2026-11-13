package cn.byteforce.seedj.starter.im.protocol;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 帧布局：16 字节大端帧头的读写与拒绝路径。 */
class ImFramesTest {

    @Test
    @DisplayName("写入后能原样读出，中文 UTF-8 body 不丢字节")
    void frameSurvivesRoundTrip() {
        byte[] body = "{\"text\":\"你好\"}".getBytes(UTF_8);
        ImFrame frame =
                new ImFrame(
                        ImFrames.MAGIC,
                        (byte) 1,
                        (byte) 1,
                        Cmd.LOGIN_REQ,
                        (byte) 0,
                        42,
                        body);
        ByteBuf buffer = Unpooled.buffer();
        try {
            ImFrames.write(buffer, frame);
            ImFrame decoded = ImFrames.read(buffer);

            assertThat(decoded.magic()).isEqualTo(ImFrames.MAGIC);
            assertThat(decoded.version()).isEqualTo((byte) 1);
            assertThat(decoded.serializeType()).isEqualTo((byte) 1);
            assertThat(decoded.cmd()).isEqualTo(Cmd.LOGIN_REQ);
            assertThat(decoded.msgType()).isEqualTo((byte) 0);
            assertThat(decoded.sequenceId()).isEqualTo(42);
            assertThat(decoded.body()).isEqualTo(body);
        } finally {
            buffer.release();
        }
    }

    @Test
    @DisplayName("不足 16 字节的帧头被拒绝")
    void shortHeaderIsRejected() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            buffer.writeInt(ImFrames.MAGIC);
            assertThatThrownBy(() -> ImFrames.read(buffer))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            buffer.release();
        }
    }

    @Test
    @DisplayName("魔数不符被拒绝")
    void badMagicIsRejected() {
        ByteBuf buffer = header(0xDEADBEEF, 0);
        try {
            assertThatThrownBy(() -> ImFrames.read(buffer))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            buffer.release();
        }
    }

    @Test
    @DisplayName("body 长度超过可读字节被拒绝")
    void bodyLengthBeyondReadableBytesIsRejected() {
        ByteBuf buffer = header(ImFrames.MAGIC, 10);
        try {
            buffer.writeBytes(new byte[] {1, 2, 3});
            assertThatThrownBy(() -> ImFrames.read(buffer))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            buffer.release();
        }
    }

    private static ByteBuf header(int magic, int bodyLength) {
        ByteBuf buffer = Unpooled.buffer();
        buffer.writeInt(magic);
        buffer.writeByte(1);
        buffer.writeByte(1);
        buffer.writeByte(Cmd.LOGIN_REQ);
        buffer.writeByte(0);
        buffer.writeInt(7);
        buffer.writeInt(bodyLength);
        return buffer;
    }
}
