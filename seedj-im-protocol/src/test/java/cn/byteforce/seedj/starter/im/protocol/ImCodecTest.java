package cn.byteforce.seedj.starter.im.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.starter.im.protocol.body.ChatSendReq;
import cn.byteforce.seedj.starter.im.protocol.body.ErrorResp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 协议模块自测：帧常量与 JSON 编解码不依赖 Spring / Netty。 */
class ImCodecTest {

    private final ImCodec codec = new ImCodec();

    @Test
    @DisplayName("响应帧带上协议魔数与 JSON 序列化标记")
    void responseFrameCarriesProtocolHeader() {
        ImFrame frame = codec.resp(Cmd.CHAT_SINGLE_REQ, 7, new ChatSendReq("m-1", "c-1", "u-2", "text", java.util.Map.of("text", "hi")));

        assertThat(frame.magic()).isEqualTo(ImFrame.MAGIC);
        assertThat(frame.cmd()).isEqualTo(Cmd.CHAT_SINGLE_REQ);
        assertThat(frame.sequenceId()).isEqualTo(7);
        assertThat(frame.body()).isNotEmpty();
    }

    @Test
    @DisplayName("body 可往返编解码")
    void bodyRoundTrips() {
        ImFrame frame = codec.resp(Cmd.CHAT_SINGLE_REQ, 1, new ChatSendReq("m-1", "c-1", "u-2", "text", java.util.Map.of("text", "hi")));

        ChatSendReq decoded = codec.decode(frame, ChatSendReq.class);

        assertThat(decoded.clientMsgId()).isEqualTo("m-1");
        assertThat(decoded.conversationId()).isEqualTo("c-1");
        assertThat(decoded.body()).containsEntry("text", "hi");
    }

    @Test
    @DisplayName("错误响应使用 ERROR 命令字与 ErrorResp body")
    void errorFrame() {
        ImFrame frame = codec.error(9, 400, "bad");

        assertThat(frame.cmd()).isEqualTo(Cmd.ERROR);
        assertThat(codec.decode(frame, ErrorResp.class).message()).isEqualTo("bad");
    }
}
