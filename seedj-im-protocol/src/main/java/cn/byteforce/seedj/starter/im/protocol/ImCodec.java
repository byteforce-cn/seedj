package cn.byteforce.seedj.starter.im.protocol;

import cn.byteforce.seedj.starter.im.protocol.body.ErrorResp;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/**
 * 帧 body 与 Java 对象之间的 JSON 编解码。
 *
 * <p>只依赖 Jackson：服务端传输层与未来的 Java 客户端共用同一份编解码，不引入 Netty 或 Spring。
 */
public final class ImCodec {

    private static final byte VERSION = 1;
    private static final byte SERIALIZE_TYPE_JSON = 1;
    private static final byte MSG_TYPE_RESPONSE = 1;

    private static final byte MSG_TYPE_NOTIFY = 2;

    private final JsonMapper mapper;

    public ImCodec() {
        this(JsonMapper.builder().build());
    }

    public ImCodec(JsonMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public <T> T decode(ImFrame frame, Class<T> type) {
        try {
            return mapper.readValue(frame.body(), type);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("报文解析失败: " + type.getSimpleName(), error);
        }
    }

    public ImFrame resp(byte cmd, int sequenceId, Object body) {
        return new ImFrame(
                ImFrame.MAGIC,
                VERSION,
                SERIALIZE_TYPE_JSON,
                cmd,
                MSG_TYPE_RESPONSE,
                sequenceId,
                write(body));
    }

    public ImFrame notify(byte cmd, Object body) {
        return new ImFrame(
                ImFrame.MAGIC, VERSION, SERIALIZE_TYPE_JSON, cmd, MSG_TYPE_NOTIFY, 0, write(body));
    }

    public ImFrame error(int sequenceId, int code, String message) {
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
