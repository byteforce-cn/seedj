package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.starter.im.protocol.ImCodec;
import cn.byteforce.seedj.starter.im.protocol.ImFrame;
import io.netty.channel.Channel;
import java.util.Collection;
import java.util.Objects;

/**
 * 本节点投递：把通知帧写到 {@link ChannelRegistry} 里该主体的连接上。
 *
 * <p>一帧编码一次、多连接复用：{@link ImFrame} 是不可变记录，编码器只读它，不持有引用。 不可写或已关闭的连接直接跳过——投递是尽力而为的，客户端重连后靠增量拉取补齐。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class LocalPushProvider implements ImPushProvider {

    private final ImCodec codec;

    private final ChannelRegistry registry;

    LocalPushProvider(ImCodec codec, ChannelRegistry registry) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void deliver(String subjectId, byte cmd, Object body) {
        if (subjectId == null || subjectId.isBlank()) {
            return;
        }
        ImFrame frame = codec.notify(cmd, body);
        for (Channel channel : registry.channels(subjectId)) {
            write(channel, frame);
        }
    }

    @Override
    public void deliverMany(Collection<String> subjectIds, byte cmd, Object body) {
        if (subjectIds == null || subjectIds.isEmpty()) {
            return;
        }
        ImFrame frame = codec.notify(cmd, body);
        for (String subjectId : subjectIds) {
            for (Channel channel : registry.channels(subjectId)) {
                write(channel, frame);
            }
        }
    }

    private static void write(Channel channel, ImFrame frame) {
        if (channel.isActive() && channel.isWritable()) {
            channel.writeAndFlush(frame);
        }
    }
}
