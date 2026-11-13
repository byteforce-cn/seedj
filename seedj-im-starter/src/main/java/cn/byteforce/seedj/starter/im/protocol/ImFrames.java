package cn.byteforce.seedj.starter.im.protocol;

import io.netty.buffer.ByteBuf;

/**
 * 帧头布局的唯一实现：TCP 编解码器、WebSocket 适配器与测试都复用它。
 *
 * <p>帧头固定 16 字节、大端：{@code magic(4) version(1) serializeType(1) cmd(1) msgType(1) sequenceId(4)
 * bodyLength(4)}。
 */
public final class ImFrames {

    /** 帧头长度（字节）。 */
    public static final int HEADER_LENGTH = 16;

    /** 协议魔数。 */
    public static final int MAGIC = 0xCAFEBABE;

    private ImFrames() {}

    /**
     * 从 {@link ByteBuf} 读取一个完整帧，读取后 {@code readerIndex} 前进。
     *
     * @param in 至少包含一个完整帧的可读缓冲区
     * @return 解码后的帧
     * @throws IllegalStateException 帧头不足、魔数不符或 body 长度非法
     */
    public static ImFrame read(ByteBuf in) {
        if (in.readableBytes() < HEADER_LENGTH) {
            throw new IllegalStateException("帧头不完整: " + in.readableBytes());
        }
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IllegalStateException("非法魔数: 0x" + Integer.toHexString(magic));
        }
        byte version = in.readByte();
        byte serializeType = in.readByte();
        byte cmd = in.readByte();
        byte msgType = in.readByte();
        int sequenceId = in.readInt();
        int bodyLength = in.readInt();
        if (bodyLength < 0 || bodyLength > in.readableBytes()) {
            throw new IllegalStateException(
                    "body 长度异常: " + bodyLength + ", 可读: " + in.readableBytes());
        }
        byte[] body = new byte[bodyLength];
        in.readBytes(body);
        return new ImFrame(magic, version, serializeType, cmd, msgType, sequenceId, body);
    }

    /**
     * 把一个帧写入 {@link ByteBuf}。
     *
     * @param out 目标缓冲区
     * @param frame 待写入的帧
     */
    public static void write(ByteBuf out, ImFrame frame) {
        out.writeInt(frame.magic());
        out.writeByte(frame.version());
        out.writeByte(frame.serializeType());
        out.writeByte(frame.cmd());
        out.writeByte(frame.msgType());
        out.writeInt(frame.sequenceId());
        byte[] body = frame.body();
        out.writeInt(body.length);
        out.writeBytes(body);
    }
}
