package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.starter.im.protocol.ImFrames;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

/**
 * TCP 帧解码：半包等待，声明长度超过上限时直接关闭连接。
 *
 * <p>每连接独立实例（{@link ByteToMessageDecoder} 有状态），不可共享。
 */
final class ImFrameDecoder extends ByteToMessageDecoder {

    private final int maxFrameLength;

    ImFrameDecoder(int maxFrameLength) {
        this.maxFrameLength = maxFrameLength;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < ImFrames.HEADER_LENGTH) {
            return;
        }
        int bodyLength = in.getInt(in.readerIndex() + 12);
        if (bodyLength < 0 || bodyLength > maxFrameLength) {
            ctx.close();
            return;
        }
        if (in.readableBytes() < ImFrames.HEADER_LENGTH + bodyLength) {
            return;
        }
        try {
            out.add(ImFrames.read(in));
        } catch (IllegalStateException malformed) {
            ctx.close();
        }
    }
}
