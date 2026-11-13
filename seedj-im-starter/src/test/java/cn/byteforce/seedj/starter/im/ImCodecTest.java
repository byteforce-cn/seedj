package cn.byteforce.seedj.starter.im;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.starter.im.protocol.Cmd;
import cn.byteforce.seedj.starter.im.protocol.ImFrame;
import cn.byteforce.seedj.starter.im.protocol.ImFrames;
import cn.byteforce.seedj.starter.im.protocol.body.ErrorResp;
import cn.byteforce.seedj.starter.im.protocol.body.LoginReq;
import cn.byteforce.seedj.starter.im.protocol.body.LoginResp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 登录/错误报文的蛇形命名 JSON 编解码。 */
class ImCodecTest {

    private final ImCodec codec = new ImCodec();

    @Test
    @DisplayName("access_token 按蛇形字段解码")
    void decodesSnakeCaseLoginReq() {
        ImFrame frame = frame("{\"access_token\":\"t1\"}");

        LoginReq req = codec.decode(frame, LoginReq.class);

        assertThat(req.accessToken()).isEqualTo("t1");
    }

    @Test
    @DisplayName("LoginResp 序列化为 user_id，不出现 userId")
    void encodesSnakeCaseLoginResp() {
        ImFrame frame = codec.resp(Cmd.LOGIN_RESP, 5, new LoginResp(0, "u1", "Alice"));

        String json = new String(frame.body(), UTF_8);

        assertThat(json).contains("\"user_id\"").doesNotContain("\"userId\"");
    }

    @Test
    @DisplayName("error 产出 ERROR 命令帧")
    void errorFrameUsesErrorCmd() {
        ImFrame frame = codec.error(9, 401, "未登录");

        assertThat(frame.cmd()).isEqualTo(Cmd.ERROR);
        assertThat(frame.sequenceId()).isEqualTo(9);
        ErrorResp error = codec.decode(frame, ErrorResp.class);
        assertThat(error.code()).isEqualTo(401);
        assertThat(error.message()).isEqualTo("未登录");
    }

    private static ImFrame frame(String json) {
        return new ImFrame(
                ImFrames.MAGIC,
                (byte) 1,
                (byte) 1,
                Cmd.LOGIN_REQ,
                (byte) 0,
                1,
                json.getBytes(UTF_8));
    }
}
