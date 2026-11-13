package cn.byteforce.seedj.starter.im.protocol;

import java.util.Arrays;

/**
 * 一个 IM 协议帧：固定 16 字节帧头 + 变长 body。
 *
 * <p>body 为 {@code null} 时归一化为空数组，避免下游每处判空。
 *
 * @param magic 魔数，固定 {@link ImFrames#MAGIC}
 * @param version 协议版本
 * @param serializeType body 序列化类型
 * @param cmd 命令字，见 {@link Cmd}
 * @param msgType 报文类型（请求/响应/通知）
 * @param sequenceId 客户端序号，响应原样带回
 * @param body JSON 字节
 */
public record ImFrame(
        int magic,
        byte version,
        byte serializeType,
        byte cmd,
        byte msgType,
        int sequenceId,
        byte[] body) {

    public ImFrame {
        body = body == null ? new byte[0] : body;
    }

    @Override
    public String toString() {
        return "ImFrame{magic=0x"
                + Integer.toHexString(magic)
                + ", version="
                + version
                + ", serializeType="
                + serializeType
                + ", cmd="
                + cmd
                + ", msgType="
                + msgType
                + ", sequenceId="
                + sequenceId
                + ", bodyLength="
                + body.length
                + ", body="
                + Arrays.toString(body)
                + '}';
    }
}
