package cn.byteforce.seedj.eventbus;

import java.util.Objects;
import java.util.Set;

/**
 * 一次订阅的元数据。
 *
 * <p><b>消费者组</b>：同一个 group 内的多个应用实例是竞争关系，一条消息只会被其中一个实例处理；
 * 不同 group 各自收到一份。单服务多实例部署时，所有实例必须使用相同的 group。
 * {@code group} 为空时由装配层回退到 {@code spring.application.name}，因此同类实例
 * 默认就会落在同一个组里。
 *
 * <p>{@code concurrency} 控制同一订阅在本实例内的并发处理数。需要严格分区顺序时设为 1。
 */
public record EventSubscription(String group, Set<String> topics, int concurrency) {

    public EventSubscription {
        Set<String> source = topics == null ? Set.of() : topics;
        if (source.isEmpty()) {
            throw new IllegalArgumentException("topics must not be empty");
        }
        if (source.stream().anyMatch(topic -> topic == null || topic.isBlank())) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        topics = Set.copyOf(source);
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be >= 1");
        }
        if (group != null) {
            group = group.isBlank() ? null : group;
        }
    }

    /**
     * 单并发订阅。
     */
    public static EventSubscription of(String group, String... topics) {
        Objects.requireNonNull(topics, "topics");
        return new EventSubscription(group, Set.of(topics), 1);
    }

    /**
     * 指定 group、topic 集合与并发度。
     */
    public static EventSubscription of(String group, Set<String> topics, int concurrency) {
        return new EventSubscription(group, topics, concurrency);
    }
}
