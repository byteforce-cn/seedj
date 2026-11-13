package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.starter.im.protocol.ImFrame;
import cn.byteforce.seedj.starter.im.protocol.ImFrames;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import java.util.List;

/**
 * WebSocket 二进制帧与 {@link ImFrame} 的适配层。
 *
 * <p>浏览器发送的 {@link BinaryWebSocketFrame} 内容就是完整帧字节；下行帧被封装回二进制帧。 无实例状态，可安全共享。
 */
@ChannelHandler.Sharable
final class ImWsAdapter extends MessageToMessageCodec<BinaryWebSocketFrame, ImFrame> {

    @Override
    protected void decode(ChannelHandlerContext ctx, BinaryWebSocketFrame msg, List<Object> out) {
        try {
            out.add(ImFrames.read(msg.content()));
        } catch (IllegalStateException malformed) {
            ctx.close();
        }
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ImFrame frame, List<Object> out) {
        ByteBuf buffer = ctx.alloc().buffer(ImFrames.HEADER_LENGTH + frame.body().length);
        ImFrames.write(buffer, frame);
        out.add(new BinaryWebSocketFrame(buffer));
    }
}
