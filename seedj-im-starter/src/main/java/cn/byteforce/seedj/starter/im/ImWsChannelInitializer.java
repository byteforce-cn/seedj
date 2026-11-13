package cn.byteforce.seedj.starter.im;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket pipeline：HTTP 升级、帧适配与读空闲检测。
 *
 * <p>握手完成后，二进制帧经 {@link ImWsAdapter} 转成 {@link cn.byteforce.seedj.starter.im.protocol.ImFrame}。
 */
final class ImWsChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final ImProperties properties;
    private final ChannelHandler[] handlers;
    private final ImWsAdapter adapter = new ImWsAdapter();

    ImWsChannelInitializer(ImProperties properties, ChannelHandler... handlers) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.handlers = handlers.clone();
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        WebSocketServerProtocolConfig config =
                WebSocketServerProtocolConfig.newBuilder()
                        .websocketPath(properties.ws().path())
                        .maxFramePayloadLength(properties.maxFrameLength())
                        .allowExtensions(true)
                        .build();

        channel.pipeline()
                .addLast(new HttpServerCodec())
                .addLast(new HttpObjectAggregator(properties.maxFrameLength()))
                .addLast(new WebSocketServerProtocolHandler(config))
                .addLast(
                        new IdleStateHandler(
                                0,
                                0,
                                properties.heartbeatTimeout().toSeconds(),
                                TimeUnit.SECONDS))
                .addLast(adapter);
        for (ChannelHandler handler : handlers) {
            channel.pipeline().addLast(handler);
        }
    }
}
