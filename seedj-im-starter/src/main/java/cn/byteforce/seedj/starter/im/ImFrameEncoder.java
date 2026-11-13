package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.starter.im.protocol.ImFrame;
import cn.byteforce.seedj.starter.im.protocol.ImFrames;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import java.util.List;

/** TCP 帧编码：无状态，每连接独立实例。 */
final class ImFrameEncoder extends MessageToMessageEncoder<ImFrame> {

    @Override
    protected void encode(ChannelHandlerContext ctx, ImFrame frame, List<Object> out) {
        ByteBuf buffer = ctx.alloc().buffer(ImFrames.HEADER_LENGTH + frame.body().length);
        ImFrames.write(buffer, frame);
        out.add(buffer);
    }
}
