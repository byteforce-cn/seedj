package cn.byteforce.seedj.starter.im;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * TCP pipeline：帧编解码与读空闲检测。
 *
 * <p>解码器/编码器有每连接状态，必须在 {@link #initChannel} 内新建。
 */
final class ImChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final ImProperties properties;
    private final ChannelHandler[] handlers;

    ImChannelInitializer(ImProperties properties, ChannelHandler... handlers) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.handlers = handlers.clone();
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        channel.pipeline()
                .addLast(
                        new IdleStateHandler(
                                0, 0, properties.heartbeatTimeout().toSeconds(), TimeUnit.SECONDS))
                .addLast(new ImFrameDecoder(properties.maxFrameLength()))
                .addLast(new ImFrameEncoder());
        for (ChannelHandler handler : handlers) {
            channel.pipeline().addLast(handler);
        }
    }
}
