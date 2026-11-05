package cn.byteforce.seedj.eventbus.spi;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * 一次消费拉取请求。
 *
 * @param group  消费者组；同一 group 内多实例竞争
 * @param topics 订阅的 topic 集合
 * @param limit  本次最多拉取条数
 * @param lease  投递租约；实例处理期间其他实例不可重复领取
 */
public record PollRequest(String group, Set<String> topics, int limit, Duration lease) {

    public PollRequest {
        Objects.requireNonNull(group, "group");
        if (group.isBlank()) {
            throw new IllegalArgumentException("group must not be blank");
        }
        Set<String> source = topics == null ? Set.of() : topics;
        if (source.isEmpty()) {
            throw new IllegalArgumentException("topics must not be empty");
        }
        topics = Set.copyOf(source);
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
    }
}
