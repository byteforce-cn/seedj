package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.starter.im.protocol.Cmd;
import cn.byteforce.seedj.starter.im.protocol.ImFrame;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.Map;

/**
 * 已握手连接的命令分发。
 *
 * <p>本阶段只实现心跳；业务命令一律回 501，未登录回 401 并关闭。无状态，可共享。
 */
@ChannelHandler.Sharable
final class ImDispatchHandler extends SimpleChannelInboundHandler<ImFrame> {

    private final ImCodec codec;

    ImDispatchHandler(ImCodec codec) {
        this.codec = codec;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ImFrame frame) {
        if (frame.cmd() == Cmd.HEARTBEAT_PING) {
            ctx.writeAndFlush(
                    codec.resp(
                            Cmd.HEARTBEAT_PONG,
                            frame.sequenceId(),
                            Map.of("ts", System.currentTimeMillis())));
            return;
        }
        if (frame.cmd() == Cmd.LOGIN_REQ) {
            return;
        }
        Caller caller = ctx.channel().attr(ImAttributes.CALLER).get();
        if (caller == null) {
            ctx.writeAndFlush(codec.error(frame.sequenceId(), 401, "未登录"))
                    .addListener(ChannelFutureListener.CLOSE);
            return;
        }
        ctx.writeAndFlush(codec.error(frame.sequenceId(), 501, "未实现: " + frame.cmd()));
    }
}
