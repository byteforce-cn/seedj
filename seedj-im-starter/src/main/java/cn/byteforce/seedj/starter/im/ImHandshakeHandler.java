package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.starter.im.protocol.Cmd;
import cn.byteforce.seedj.starter.im.protocol.ImFrame;
import cn.byteforce.seedj.starter.im.protocol.body.LoginReq;
import cn.byteforce.seedj.starter.im.protocol.body.LoginResp;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 登录握手：{@code LOGIN_REQ} 换取 {@link Caller}，并把连接登记到 {@link ChannelRegistry}。
 *
 * <p>非登录帧要求已握手；失败一律回 401 并关闭连接。无状态，可共享。
 */
@ChannelHandler.Sharable
final class ImHandshakeHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ImHandshakeHandler.class);

    private final ImCodec codec;
    private final ImJwtAuthenticator authenticator;
    private final ChannelRegistry registry;

    ImHandshakeHandler(
            ImCodec codec, ImJwtAuthenticator authenticator, ChannelRegistry registry) {
        this.codec = codec;
        this.authenticator = authenticator;
        this.registry = registry;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ImFrame frame)) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (frame.cmd() != Cmd.LOGIN_REQ) {
            if (ctx.channel().attr(ImAttributes.CALLER).get() == null) {
                reject(ctx, frame.sequenceId(), "未登录");
                return;
            }
            ctx.fireChannelRead(msg);
            return;
        }
        handleLogin(ctx, frame);
    }

    private void handleLogin(ChannelHandlerContext ctx, ImFrame frame) {
        Caller caller;
        try {
            LoginReq request = codec.decode(frame, LoginReq.class);
            caller = authenticator.authenticate(request.accessToken());
        } catch (RuntimeException failure) {
            log.debug("IM 登录失败: {}", failure.getMessage(), failure);
            rejectAuthentication(ctx, frame.sequenceId());
            return;
        }

        Caller previous = ctx.channel().attr(ImAttributes.CALLER).getAndSet(caller);
        if (previous != null) {
            registry.unbind(previous.id(), ctx.channel());
        }
        registry.bind(caller.id(), ctx.channel());
        ctx.writeAndFlush(
                codec.resp(
                        Cmd.LOGIN_RESP,
                        frame.sequenceId(),
                        new LoginResp(0, caller.id(), displayName(caller))));
    }

    private void reject(ChannelHandlerContext ctx, int sequenceId, String reason) {
        String message = reason == null || reason.isBlank() ? "认证失败" : reason;
        ctx.writeAndFlush(codec.error(sequenceId, 401, message))
                .addListener(ChannelFutureListener.CLOSE);
    }

    private void rejectAuthentication(ChannelHandlerContext ctx, int sequenceId) {
        reject(ctx, sequenceId, "认证失败");
    }

    private static String displayName(Caller caller) {
        for (String claim : new String[] {"name", "preferred_username", "display_name"}) {
            Object value = caller.attributes().get(claim);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return caller.username();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Caller caller = ctx.channel().attr(ImAttributes.CALLER).get();
        if (caller != null) {
            registry.unbind(caller.id(), ctx.channel());
        }
        super.channelInactive(ctx);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof IdleStateEvent) {
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, event);
    }
}
