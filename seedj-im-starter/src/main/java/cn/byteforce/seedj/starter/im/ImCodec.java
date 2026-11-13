package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.starter.im.protocol.Cmd;
import cn.byteforce.seedj.starter.im.protocol.ImFrame;
import cn.byteforce.seedj.starter.im.protocol.ImFrames;
import cn.byteforce.seedj.starter.im.protocol.body.ErrorResp;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/**
 * 帧 body 与 Java 对象之间的 JSON 编解码。
 *
 * <p>只服务本 starter 的传输层，因此保持包私有；协议类型本身在 {@code protocol} 包公开。
 */
final class ImCodec {

    private static final byte VERSION = 1;
    private static final byte SERIALIZE_TYPE_JSON = 1;
    private static final byte MSG_TYPE_RESPONSE = 1;

    private final JsonMapper mapper;

    ImCodec() {
        this(JsonMapper.builder().build());
    }

    ImCodec(JsonMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    <T> T decode(ImFrame frame, Class<T> type) {
        try {
            return mapper.readValue(frame.body(), type);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("报文解析失败: " + type.getSimpleName(), error);
        }
    }

    ImFrame resp(byte cmd, int sequenceId, Object body) {
        return new ImFrame(
                ImFrames.MAGIC,
                VERSION,
                SERIALIZE_TYPE_JSON,
                cmd,
                MSG_TYPE_RESPONSE,
                sequenceId,
                write(body));
    }

    ImFrame error(int sequenceId, int code, String message) {
        return resp(Cmd.ERROR, sequenceId, new ErrorResp(code, message));
    }

    private byte[] write(Object body) {
        if (body == null) {
            return new byte[0];
        }
        try {
            return mapper.writeValueAsBytes(body);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("报文序列化失败", error);
        }
    }
}
