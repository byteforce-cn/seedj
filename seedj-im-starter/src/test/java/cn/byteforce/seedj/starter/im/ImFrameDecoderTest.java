package cn.byteforce.seedj.starter.im;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.starter.im.protocol.Cmd;
import cn.byteforce.seedj.starter.im.protocol.ImFrame;
import cn.byteforce.seedj.starter.im.protocol.ImFrames;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TCP 帧解码的半包等待。 */
class ImFrameDecoderTest {

    @Test
    @DisplayName("body 未到齐时等待，补齐后只产出一个完整帧")
    void waitsForCompleteBody() {
        EmbeddedChannel channel = new EmbeddedChannel(new ImFrameDecoder(1024));
        try {
            byte[] body = "{\"access_token\":\"abc\"}".getBytes(UTF_8);
            ByteBuf first = header(body.length);
            first.writeBytes(body, 0, 5);

            boolean firstProduced = channel.writeInbound(first);
            assertThat(firstProduced).isFalse();
            assertThat(channel.<ImFrame>readInbound()).isNull();

            boolean secondProduced =
                    channel.writeInbound(Unpooled.wrappedBuffer(body, 5, body.length - 5));
            assertThat(secondProduced).isTrue();
            ImFrame decoded = channel.readInbound();
            assertThat(decoded).isNotNull();
            assertThat(decoded.sequenceId()).isEqualTo(11);
            assertThat(decoded.body()).isEqualTo(body);
            assertThat(channel.<ImFrame>readInbound()).isNull();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static ByteBuf header(int bodyLength) {
        ByteBuf buffer = Unpooled.buffer();
        buffer.writeInt(ImFrames.MAGIC);
        buffer.writeByte(1);
        buffer.writeByte(1);
        buffer.writeByte(Cmd.LOGIN_REQ);
        buffer.writeByte(0);
        buffer.writeInt(11);
        buffer.writeInt(bodyLength);
        return buffer;
    }
}
