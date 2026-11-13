package cn.byteforce.seedj.starter.im;

import io.netty.channel.Channel;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线连接注册表：subject → 该主体的全部活动连接。
 *
 * <p>同一用户多端登录时保留全部连接；只有最后一条断开会移除 subject。
 */
final class ChannelRegistry {

    private final ConcurrentHashMap<String, Set<Channel>> bySubject = new ConcurrentHashMap<>();

    void bind(String subject, Channel channel) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(channel, "channel");
        bySubject.computeIfAbsent(subject, key -> ConcurrentHashMap.newKeySet()).add(channel);
    }

    void unbind(String subject, Channel channel) {
        if (subject == null || channel == null) {
            return;
        }
        bySubject.computeIfPresent(
                subject,
                (key, channels) -> {
                    channels.remove(channel);
                    return channels.isEmpty() ? null : channels;
                });
    }

    /** 某主体的连接快照；无连接时为空集合。 */
    Set<Channel> channels(String subject) {
        Set<Channel> channels = subject == null ? null : bySubject.get(subject);
        return channels == null ? Set.of() : Set.copyOf(channels);
    }

    /** 当前有连接的主体数量。 */
    int size() {
        return bySubject.size();
    }
}
