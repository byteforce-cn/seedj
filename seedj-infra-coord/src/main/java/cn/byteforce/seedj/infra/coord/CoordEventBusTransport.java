package cn.byteforce.seedj.infra.coord;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;
import cn.byteforce.coord.sdk.mq.v2.MqV2Client;
import cn.byteforce.coord.sdk.mq.v2.MqV2Publish;
import cn.byteforce.coord.sdk.mq.v2.MqV2TopicConfig;
import cn.byteforce.coord.sdk.mq.v2.MqV2TopicCreated;
import cn.byteforce.coord.sdk.mq.v2.MqV2TopicDescription;
import cn.byteforce.seedj.eventbus.EventBusErrorCode;
import cn.byteforce.seedj.eventbus.EventBusException;
import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.spi.EventBusTransport;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * coord MQ v2 发布适配。
 *
 * <p>topic 元数据（epoch/分区数）本地缓存；{@code TOPIC_EPOCH_STALE} 时刷新并重试一次。 分区键为空时用 eventId，保证同一 eventId
 * 的重试落在同一分区并命中去重。
 */
final class CoordEventBusTransport implements EventBusTransport {

    private final CoordEndpoint endpoint;
    private final MqV2Client mq;
    private final String producer;
    private final int partitions;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, TopicMeta> topics = new ConcurrentHashMap<>();

    private volatile boolean closed;

    CoordEventBusTransport(CoordEndpoint endpoint, MqV2Client mq, String producer, int partitions) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.mq = Objects.requireNonNull(mq, "mq");
        if (producer == null || producer.isBlank()) {
            throw new IllegalArgumentException("coord eventbus producer must not be blank");
        }
        if (partitions < 1) {
            throw new IllegalArgumentException("coord eventbus partitions must be >= 1");
        }
        this.producer = producer;
        this.partitions = partitions;
    }

    @Override
    public CompletionStage<Void> send(EventMessage message) {
        Objects.requireNonNull(message, "message");
        if (closed) {
            return CompletableFuture.failedFuture(
                    new EventBusException(
                            EventBusErrorCode.PROVIDER_CLOSED,
                            "coord eventbus transport is closed"));
        }
        try {
            return CompletableFuture.supplyAsync(
                    () -> {
                        publish(message);
                        return null;
                    },
                    executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(
                    new EventBusException(
                            EventBusErrorCode.PROVIDER_CLOSED,
                            "coord eventbus transport is closed",
                            e));
        }
    }

    private void publish(EventMessage message) {
        try {
            TopicMeta meta = resolve(message.topic());
            try {
                publishOnce(message, meta);
            } catch (CoordException e) {
                if (e.getErrorCode() != ErrorCode.TOPIC_EPOCH_STALE) {
                    throw CoordErrors.eventBus(e, EventBusErrorCode.SEND_FAILED, endpoint);
                }
                topics.remove(message.topic());
                publishOnce(message, resolve(message.topic()));
            }
        } catch (CoordException e) {
            throw CoordErrors.eventBus(e, EventBusErrorCode.SEND_FAILED, endpoint);
        }
    }

    private void publishOnce(EventMessage message, TopicMeta meta) {
        String key = message.partitionKey() == null ? message.eventId() : message.partitionKey();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.putAll(message.headers());
        headers.put("eventId", message.eventId());
        headers.put("eventType", message.eventType());
        headers.put("eventVersion", Integer.toString(message.eventVersion()));
        headers.put("occurredAt", message.occurredAt().toString());

        mq.publish(
                new MqV2Publish(
                        message.topic(),
                        Math.floorMod(key.hashCode(), meta.partitions()),
                        meta.epoch(),
                        key.getBytes(StandardCharsets.UTF_8),
                        message.payload(),
                        message.eventId(),
                        headers,
                        producer));
    }

    @Override
    public void ensureTopic(String topic) {
        requireTopic(topic);
        try {
            resolve(topic);
        } catch (CoordException e) {
            throw CoordErrors.eventBus(e, EventBusErrorCode.TRANSPORT_UNAVAILABLE, endpoint);
        }
    }

    @Override
    public boolean topicExists(String topic) {
        requireTopic(topic);
        try {
            describe(topic);
            return true;
        } catch (CoordException e) {
            if (e.getErrorCode() == ErrorCode.TOPIC_NOT_FOUND) {
                return false;
            }
            throw CoordErrors.eventBus(e, EventBusErrorCode.TRANSPORT_UNAVAILABLE, endpoint);
        }
    }

    private TopicMeta resolve(String topic) {
        TopicMeta cached = topics.get(topic);
        if (cached != null) {
            return cached;
        }
        try {
            return describe(topic);
        } catch (CoordException e) {
            if (e.getErrorCode() != ErrorCode.TOPIC_NOT_FOUND) {
                throw e;
            }
            try {
                MqV2TopicCreated created =
                        mq.createTopic(
                                topic,
                                partitions,
                                "seedj:create:" + topic,
                                MqV2TopicConfig.defaults());
                return cache(topic, created.topicEpoch(), created.partitionCount());
            } catch (CoordException createError) {
                if (createError.getErrorCode() != ErrorCode.TOPIC_EXISTS) {
                    throw createError;
                }
                return describe(topic);
            }
        }
    }

    private TopicMeta describe(String topic) {
        MqV2TopicDescription description = mq.describeTopic(topic);
        return cache(topic, description.topicEpoch(), description.partitionCount());
    }

    private TopicMeta cache(String topic, long epoch, int partitionCount) {
        TopicMeta meta = new TopicMeta(epoch, Math.max(1, partitionCount));
        topics.put(topic, meta);
        return meta;
    }

    private static void requireTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("eventbus topic must not be blank");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        topics.clear();
        executor.shutdown();
    }

    private record TopicMeta(long epoch, int partitions) {}
}
