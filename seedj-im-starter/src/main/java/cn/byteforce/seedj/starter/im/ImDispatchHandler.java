package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.starter.im.protocol.ImCodec;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.starter.im.protocol.Cmd;
import cn.byteforce.seedj.starter.im.protocol.ImFrame;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 已握手连接的命令分发。
 *
 * <p>心跳与登录在 IO 线程内处理；注册表命中的命令字交给 {@link ImSessionExecutor}，因此处理器可以 直接访问数据库。未注册的命令字回 {@code
 * 501}，未登录回 {@code 401} 并关闭。无状态，可共享。
 *
 * <p>处理器抛出的 {@code ContractException} 映射成协议错误码，其它异常统一 {@code 500}，细节只进日志。
 */
@ChannelHandler.Sharable
final class ImDispatchHandler extends SimpleChannelInboundHandler<ImFrame> {

    private static final Logger log = LoggerFactory.getLogger(ImDispatchHandler.class);

    private static final int INTERNAL_ERROR = 500;

    private static final int TOO_MANY_REQUESTS = 429;

    private final ImCodec codec;

    private final ImCommandRegistry registry;

    private volatile ImSessionExecutor executor;

    private final ObjectProvider<ImSessionExecutor> executors;

    ImDispatchHandler(ImCodec codec) {
        this(codec, new ImCommandRegistry(java.util.List.of()), null, null);
    }

    ImDispatchHandler(ImCodec codec, ImCommandRegistry registry) {
        this(codec, registry, null, null);
    }

    ImDispatchHandler(ImCodec codec, ImCommandRegistry registry, ImSessionExecutor executor) {
        this(codec, registry, executor, null);
    }

    ImDispatchHandler(
            ImCodec codec,
            ImCommandRegistry registry,
            ImSessionExecutor executor,
            ObjectProvider<ImSessionExecutor> executors) {
        this.codec = codec;
        this.registry = registry;
        this.executor = executor;
        this.executors = executors;
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
        Optional<ImCommandHandler<?>> handler = registry.find(frame.cmd());
        if (handler.isEmpty()) {
            ctx.writeAndFlush(codec.error(frame.sequenceId(), 501, "未实现: " + frame.cmd()));
            return;
        }
        ImSessionExecutor lanes = executor();
        if (lanes == null) {
            dispatch(ctx, frame, caller, handler.get());
            return;
        }
        try {
            lanes.execute(caller.id(), () -> dispatch(ctx, frame, caller, handler.get()));
        } catch (RejectedExecutionException overloaded) {
            ctx.writeAndFlush(codec.error(frame.sequenceId(), TOO_MANY_REQUESTS, "服务繁忙，请稍后重试"));
        }
    }

    private ImSessionExecutor executor() {
        ImSessionExecutor resolved = executor;
        if (resolved == null && executors != null) {
            resolved = executors.getIfAvailable();
            executor = resolved;
        }
        return resolved;
    }

    private void dispatch(
            ChannelHandlerContext ctx, ImFrame frame, Caller caller, ImCommandHandler<?> handler) {
        ImCallContext context =
                new ImCallContext(
                        caller,
                        ctx.channel().attr(ImAttributes.DEVICE_ID).get(),
                        frame.sequenceId(),
                        (notification, body) ->
                                ctx.writeAndFlush(codec.notify(notification, body)));
        try {
            ImReply reply = invoke(handler, context, frame);
            if (reply != null) {
                ctx.writeAndFlush(codec.resp(reply.cmd(), frame.sequenceId(), reply.body()));
            }
        } catch (ContractException rejected) {
            ctx.writeAndFlush(
                    codec.error(
                            frame.sequenceId(),
                            rejected.code().httpStatus(),
                            rejected.getMessage()));
        } catch (RuntimeException failure) {
            log.warn("IM 命令处理失败: cmd={}", frame.cmd(), failure);
            ctx.writeAndFlush(codec.error(frame.sequenceId(), INTERNAL_ERROR, "内部错误"));
        }
    }

    @SuppressWarnings("unchecked")
    private ImReply invoke(ImCommandHandler<?> handler, ImCallContext context, ImFrame frame) {
        ImCommandHandler<Object> typed = (ImCommandHandler<Object>) handler;
        Object body = frame.body().length == 0 ? null : codec.decode(frame, typed.bodyType());
        return typed.handle(context, body);
    }
}
