package cn.byteforce.seedj.starter.im;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.net.InetSocketAddress;
import java.util.Objects;
import org.springframework.context.SmartLifecycle;

/**
 * TCP 长连接服务：绑定 {@code seedj.im.tcp.port}，随 Spring 上下文启停。
 *
 * <p>启动是同步的，构造完成后即可通过 {@link #boundPort()} 读取实际端口（测试绑定 0）。
 */
final class ImTcpServer implements SmartLifecycle {

    private final ImProperties properties;
    private final ImChannelInitializer initializer;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile boolean running;

    ImTcpServer(ImProperties properties, ImChannelInitializer initializer) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.initializer = Objects.requireNonNull(initializer, "initializer");
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        bossGroup = new NioEventLoopGroup(1);
        workerGroup =
                new NioEventLoopGroup(
                        Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() * 2)));
        try {
            serverChannel =
                    new ServerBootstrap()
                            .group(bossGroup, workerGroup)
                            .channel(NioServerSocketChannel.class)
                            .childOption(ChannelOption.TCP_NODELAY, true)
                            .childOption(ChannelOption.SO_KEEPALIVE, true)
                            .childHandler(initializer)
                            .bind(properties.tcp().port())
                            .syncUninterruptibly()
                            .channel();
            running = true;
        } catch (RuntimeException bindFailure) {
            shutdownGroups();
            throw bindFailure;
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        Channel channel = serverChannel;
        serverChannel = null;
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
        shutdownGroups();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 实际监听端口；未启动或已停止时返回 -1。 */
    int boundPort() {
        Channel channel = serverChannel;
        if (channel == null || !channel.isActive()) {
            return -1;
        }
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    private void shutdownGroups() {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup = null;
        }
    }
}
