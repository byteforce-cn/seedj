package cn.byteforce.seedj.starter.im;

import io.netty.channel.Channel;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 在线连接注册表：subject → 该主体的全部活动连接，按建立顺序排列。
 *
 * <p>同一用户多端登录时保留全部连接；只有最后一条断开会移除 subject。顺序是「最早连接在前」， 供会话数超限时按 {@code evict-oldest} 策略挑选要关闭的连接。
 *
 * <p>每个 subject 的映射在 {@code compute} 的 key 锁内修改，读写都同步在同一个 map 监视器上： 并发 {@code bind}/{@code unbind}
 * 不会把新连接丢进已被移除的集合。
 */
final class ChannelRegistry {

    private final ConcurrentHashMap<String, LinkedHashMap<Channel, Long>> bySubject =
            new ConcurrentHashMap<>();

    private final AtomicLong order = new AtomicLong();

    void bind(String subject, Channel channel) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(channel, "channel");
        bySubject.compute(
                subject,
                (key, existing) -> {
                    LinkedHashMap<Channel, Long> channels =
                            existing == null ? new LinkedHashMap<>() : existing;
                    synchronized (channels) {
                        channels.put(channel, order.incrementAndGet());
                    }
                    return channels;
                });
    }

    void unbind(String subject, Channel channel) {
        if (subject == null || channel == null) {
            return;
        }
        bySubject.computeIfPresent(
                subject,
                (key, channels) -> {
                    synchronized (channels) {
                        channels.remove(channel);
                        return channels.isEmpty() ? null : channels;
                    }
                });
    }

    /** 某主体的连接快照，最早建立的在前；无连接时为空集合。 */
    Set<Channel> channels(String subject) {
        LinkedHashMap<Channel, Long> channels = subject == null ? null : bySubject.get(subject);
        if (channels == null) {
            return Set.of();
        }
        synchronized (channels) {
            return new LinkedHashSet<>(channels.keySet());
        }
    }

    /** 某主体当前的连接数。 */
    int count(String subject) {
        LinkedHashMap<Channel, Long> channels = subject == null ? null : bySubject.get(subject);
        if (channels == null) {
            return 0;
        }
        synchronized (channels) {
            return channels.size();
        }
    }

    /** 某主体最早建立的连接。 */
    Optional<Channel> oldest(String subject) {
        LinkedHashMap<Channel, Long> channels = subject == null ? null : bySubject.get(subject);
        if (channels == null) {
            return Optional.empty();
        }
        synchronized (channels) {
            return channels.keySet().stream().findFirst();
        }
    }

    /** 当前有连接的端数量。 */
    int size() {
        return bySubject.size();
    }
}
