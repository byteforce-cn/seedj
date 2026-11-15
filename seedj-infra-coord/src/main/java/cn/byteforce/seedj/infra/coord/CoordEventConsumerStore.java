package cn.byteforce.seedj.infra.coord;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;
import cn.byteforce.coord.sdk.mq.v2.MqV2Client;
import cn.byteforce.coord.sdk.mq.v2.MqV2Consumer;
import cn.byteforce.coord.sdk.mq.v2.MqV2ConsumerOptions;
import cn.byteforce.coord.sdk.mq.v2.MqV2Message;
import cn.byteforce.coord.sdk.mq.v2.MqV2Poll;
import cn.byteforce.coord.sdk.mq.v2.MqV2PollResult;
import cn.byteforce.coord.sdk.mq.v2.MqV2TopicConfig;
import cn.byteforce.seedj.eventbus.EventBusErrorCode;
import cn.byteforce.seedj.eventbus.EventBusException;
import cn.byteforce.seedj.eventbus.spi.DeadLetter;
import cn.byteforce.seedj.eventbus.spi.DeliveryFailure;
import cn.byteforce.seedj.eventbus.spi.EventConsumerStore;
import cn.byteforce.seedj.eventbus.spi.PollRequest;
import cn.byteforce.seedj.eventbus.spi.ReceivedEvent;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.core.JacksonException;

/**
 * 用 coord MQ v2 + KV 投递账本组合出的消费端语义。
 *
 * <p>coord 的 MQ 只有 poll/ack（位点提交），没有单条消息的租约、延迟重试与按组合查询死信； seedj 的 {@link EventConsumerStore}
 * 要求「同名消息同一时刻只被一个实例领取」「租约到期可 重领」「retry(delay) 到期才可见」「死信可查」。组合方式：
 *
 * <ol>
 *   <li>KV 账本（{@code group/topic/partition/offset} 一条记录）是投递状态的唯一事实源，带 {@code deliveryId / attempts
 *       / leaseUntil / retryAfter}；
 *   <li>领取时先写账本、再 ack 位点：崩溃后位点不丢消息（账本里还在），retry 也不阻塞分区；
 *   <li>租约与重试延迟由**本地时钟**判定（契约要求亚秒级，coord 没有亚秒 TTL）；
 *   <li>死信写 KV（带 group 与 failure），{@code deadLetters} 按前缀扫描。
 * </ol>
 *
 * <p>边界：到期重投按位点回读 MQ，消息已被 retention 清掉时转入死信 （{@code seedj.retention.expired}）；账本只存元数据，payload 不进
 * KV（死信除外）。 账本每次 poll 只扫描前 {@code scan-limit} 条；账本大小 ≈ 在飞消息数，正常远小于该值。
 */
final class CoordEventConsumerStore implements EventConsumerStore {

    private static final String INFLIGHT = "inflight/";
    private static final String DLQ = "dlq/";
    private static final String RETENTION_EXPIRED = "seedj.retention.expired";

    private final CoordKvStore store;
    private final MqV2Client mq;
    private final CoordEndpoint endpoint;
    private final CoordKeySpace keys;
    private final String memberId;
    private final int maxMessages;
    private final int scanLimit;
    private final int partitions;
    private final Clock clock;
    private final ConcurrentHashMap<String, GroupConsumer> groups = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> epochs = new ConcurrentHashMap<>();

    private volatile boolean closed;

    CoordEventConsumerStore(
            CoordKvStore store,
            MqV2Client mq,
            CoordEndpoint endpoint,
            CoordKeySpace keys,
            String memberId,
            int maxMessages,
            int scanLimit,
            int partitions,
            Clock clock) {
        this.store = store;
        this.mq = mq;
        this.endpoint = endpoint;
        this.keys = keys;
        this.memberId = memberId;
        this.maxMessages = maxMessages;
        this.scanLimit = scanLimit;
        this.partitions = partitions;
        this.clock = clock;
    }

    @Override
    public List<ReceivedEvent> poll(PollRequest request) {
        requireOpen();
        long leaseMs = request.lease().toMillis();
        List<ReceivedEvent> events = new ArrayList<>(request.limit());
        try {
            GroupConsumer group = consumer(request.group(), request.topics());
            redeliverDue(group, request, leaseMs, events);
            if (events.size() < request.limit()) {
                claimNew(group, request, leaseMs, events);
            }
            return events;
        } catch (CoordException e) {
            throw CoordErrors.consumer(e, endpoint);
        }
    }

    /** 先补到期的账本记录，保证 retry 与多实例接管不被新消息淹没。 */
    private void redeliverDue(
            GroupConsumer group, PollRequest request, long leaseMs, List<ReceivedEvent> events) {
        long now = clock.millis();
        for (CoordKvStore.Entry entry :
                store.scan(keys.key(INFLIGHT + segment(request.group()) + "/"), scanLimit)) {
            if (events.size() >= request.limit()) {
                return;
            }
            Delivery delivery = delivery(entry);
            if (!request.topics().contains(delivery.topic()) || !delivery.due(now)) {
                continue;
            }
            String deliveryKey = inflightKey(request.group(), delivery);
            Delivery claimed = delivery.claim(newDeliveryId(delivery), now, leaseMs);
            if (!store.replace(deliveryKey, entry.value(), json(claimed))) {
                continue;
            }
            MqV2Message message = fetch(claimed);
            if (message == null) {
                deadLetterRetentionExpired(claimed, entry.value());
                continue;
            }
            events.add(received(claimed, message));
        }
    }

    /** 领取新消息：先记账再 ack 位点，账本因此成为重投的唯一入口。 */
    private void claimNew(
            GroupConsumer group, PollRequest request, long leaseMs, List<ReceivedEvent> events) {
        long now = clock.millis();
        for (MqV2Message message : group.consumer().poll()) {
            if (events.size() >= request.limit()) {
                return;
            }
            ReceivedEvent received = claim(message, request.group(), leaseMs, now);
            group.consumer().ack(message);
            if (received != null) {
                events.add(received);
            }
        }
    }

    private ReceivedEvent claim(MqV2Message message, String groupName, long leaseMs, long now) {
        Delivery fresh =
                new Delivery(
                        groupName,
                        "",
                        message.topic(),
                        message.partition(),
                        message.offset(),
                        0,
                        0,
                        0);
        String deliveryKey = inflightKey(groupName, fresh);
        Optional<CoordKvStore.Entry> existing = store.get(deliveryKey);
        if (existing.isPresent()) {
            Delivery previous = delivery(existing.get());
            if (!previous.due(now)) {
                return null;
            }
            Delivery claimed = previous.claim(newDeliveryId(previous), now, leaseMs);
            return store.replace(deliveryKey, existing.get().value(), json(claimed))
                    ? received(claimed, message)
                    : null;
        }
        Delivery claimed = fresh.claim(newDeliveryId(fresh), now, leaseMs);
        return store.putIfAbsent(deliveryKey, json(claimed)) ? received(claimed, message) : null;
    }

    @Override
    public void ack(ReceivedEvent event) {
        requireOpen();
        try {
            Located located = locate(event);
            long now = clock.millis();
            if (!store.removeIf(located.key(), entry -> ackable(entry, event.deliveryId(), now))) {
                throw leaseLost();
            }
        } catch (CoordException e) {
            throw CoordErrors.consumer(e, endpoint);
        }
    }

    @Override
    public void retry(ReceivedEvent event, Duration delay, DeliveryFailure failure) {
        requireOpen();
        Objects.requireNonNull(delay, "delay");
        try {
            Located located = locate(event);
            long now = clock.millis();
            long delayMs = Math.max(0, delay.toMillis());
            boolean applied =
                    store.replaceIf(
                            located.key(),
                            entry -> ackable(entry, event.deliveryId(), now),
                            entry -> json(delivery(entry).retry(now, delayMs)));
            if (!applied) {
                throw leaseLost();
            }
        } catch (CoordException e) {
            throw CoordErrors.consumer(e, endpoint);
        }
    }

    @Override
    public void deadLetter(ReceivedEvent event, DeliveryFailure failure) {
        requireOpen();
        Objects.requireNonNull(failure, "failure");
        try {
            Located located = locate(event);
            CoordKvStore.Entry entry = current(located, event.deliveryId());
            Delivery delivery = delivery(entry);
            StoredDeadLetter dead =
                    new StoredDeadLetter(
                            event.deliveryId(),
                            delivery.topic(),
                            event.group(),
                            StoredMessage.of(event.message()),
                            failure,
                            clock.millis());
            // 先落死信再删账本：中途崩溃最多重复一条同 id 的死信，不会丢消息。
            store.put(dlqKey(event.group(), delivery.topic(), event.deliveryId()), json(dead));
            store.removeIf(
                    located.key(),
                    candidate -> delivery(candidate).deliveryId().equals(event.deliveryId()));
        } catch (CoordException e) {
            throw CoordErrors.consumer(e, endpoint);
        }
    }

    @Override
    public List<DeadLetter> deadLetters(String topic, String group, int limit) {
        requireOpen();
        if (limit <= 0) {
            return List.of();
        }
        try {
            List<DeadLetter> letters = new ArrayList<>();
            for (CoordKvStore.Entry entry :
                    store.scan(
                            keys.key(DLQ + segment(group) + "/" + segment(topic) + "/"), limit)) {
                letters.add(deadLetter(entry));
            }
            letters.sort(Comparator.comparing(DeadLetter::deadLetteredAt));
            return letters;
        } catch (CoordException e) {
            throw CoordErrors.consumer(e, endpoint);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (GroupConsumer group : groups.values()) {
            group.consumer().close();
        }
        groups.clear();
    }

    private GroupConsumer consumer(String group, Set<String> topics) {
        Set<String> copy = Set.copyOf(topics);
        return groups.compute(
                group,
                (name, existing) -> {
                    if (existing != null) {
                        if (!existing.topics().equals(copy)) {
                            throw new EventBusException(
                                    EventBusErrorCode.INVALID_SUBSCRIBER,
                                    "consumer group "
                                            + name
                                            + " already polls topics "
                                            + existing.topics()
                                            + ", cannot switch to "
                                            + copy);
                        }
                        return existing;
                    }
                    for (String topic : copy) {
                        ensureTopic(topic);
                    }
                    MqV2Consumer consumer =
                            MqV2Consumer.join(
                                    mq,
                                    MqV2ConsumerOptions.builder(name, List.copyOf(copy))
                                            .memberId(memberId)
                                            .waitMs(0)
                                            .maxMessages(maxMessages)
                                            .build());
                    return new GroupConsumer(copy, consumer);
                });
    }

    private void ensureTopic(String topic) {
        try {
            mq.describeTopic(topic);
        } catch (CoordException e) {
            if (e.getErrorCode() != ErrorCode.TOPIC_NOT_FOUND) {
                throw e;
            }
            try {
                mq.createTopic(
                        topic, partitions, "seedj:create:" + topic, MqV2TopicConfig.defaults());
            } catch (CoordException createError) {
                if (createError.getErrorCode() != ErrorCode.TOPIC_EXISTS) {
                    throw createError;
                }
            }
        }
    }

    /** 按位点回读原消息；retention 已清掉时返回 null。 */
    private MqV2Message fetch(Delivery delivery) {
        long epoch =
                epochs.computeIfAbsent(
                        delivery.topic(), topic -> mq.describeTopic(topic).topicEpoch());
        try {
            MqV2PollResult result =
                    mq.poll(
                            new MqV2Poll(
                                    delivery.topic(),
                                    delivery.partition(),
                                    epoch,
                                    delivery.offset(),
                                    1,
                                    0,
                                    0));
            for (MqV2Message message : result.messages()) {
                if (message.offset() == delivery.offset()) {
                    return message;
                }
            }
            return null;
        } catch (CoordException e) {
            if (e.getErrorCode() == ErrorCode.OFFSET_OUT_OF_RANGE
                    || e.getErrorCode() == ErrorCode.NOT_FOUND) {
                return null;
            }
            throw e;
        }
    }

    private void deadLetterRetentionExpired(Delivery delivery, byte[] entryValue) {
        StoredDeadLetter dead =
                new StoredDeadLetter(
                        delivery.deliveryId(),
                        delivery.topic(),
                        delivery.group(),
                        StoredMessage.retentionGone(delivery.topic()),
                        new DeliveryFailure(
                                RETENTION_EXPIRED, "message is no longer readable", false),
                        clock.millis());
        store.put(dlqKey(delivery.group(), delivery.topic(), delivery.deliveryId()), json(dead));
        store.removeIfMatch(inflightKey(delivery.group(), delivery), entryValue);
    }

    /** 账本记录仍属于该 deliveryId 且租约未过期时，才允许结算。 */
    private boolean ackable(CoordKvStore.Entry entry, String deliveryId, long now) {
        Delivery delivery = delivery(entry);
        return delivery.deliveryId().equals(deliveryId) && now < delivery.leaseUntilMs();
    }

    private CoordKvStore.Entry current(Located located, String deliveryId) {
        Optional<CoordKvStore.Entry> entry = store.readCurrent(located.key());
        if (entry.isEmpty() || !delivery(entry.get()).deliveryId().equals(deliveryId)) {
            throw leaseLost();
        }
        return entry.get();
    }

    /** deliveryId 自带定位信息，重启后 ack/retry/deadLetter 仍能落到正确的账本记录。 */
    private Located locate(ReceivedEvent event) {
        String[] parts = event.deliveryId().split(":", 4);
        if (parts.length != 4) {
            throw leaseLost();
        }
        try {
            String topic =
                    new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            int partition = Integer.parseInt(parts[1]);
            long offset = Long.parseLong(parts[2]);
            return new Located(
                    keys.key(
                            INFLIGHT
                                    + segment(event.group())
                                    + "/"
                                    + segment(topic)
                                    + "/"
                                    + String.format("%010d", partition)
                                    + "/"
                                    + String.format("%020d", offset)));
        } catch (IllegalArgumentException e) {
            throw leaseLost();
        }
    }

    private String inflightKey(String group, Delivery delivery) {
        return keys.key(
                INFLIGHT
                        + segment(group)
                        + "/"
                        + segment(delivery.topic())
                        + "/"
                        + String.format("%010d", delivery.partition())
                        + "/"
                        + String.format("%020d", delivery.offset()));
    }

    private String dlqKey(String group, String topic, String deliveryId) {
        return keys.key(DLQ + segment(group) + "/" + segment(topic) + "/" + deliveryId);
    }

    private static String newDeliveryId(Delivery delivery) {
        return segment(delivery.topic())
                + ":"
                + delivery.partition()
                + ":"
                + delivery.offset()
                + ":"
                + UUID.randomUUID();
    }

    private static String segment(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static ReceivedEvent received(Delivery delivery, MqV2Message message) {
        return new ReceivedEvent(
                delivery.deliveryId(),
                delivery.group(),
                StoredMessage.toEvent(message),
                delivery.attempts(),
                Instant.ofEpochMilli(delivery.leaseUntilMs()));
    }

    private Delivery delivery(CoordKvStore.Entry entry) {
        try {
            return CoordJson.read(entry.value(), Delivery.class);
        } catch (JacksonException e) {
            throw new EventBusException(
                    EventBusErrorCode.INTERNAL_ERROR,
                    "coord consumer ledger record is corrupt at " + endpoint,
                    e);
        }
    }

    private DeadLetter deadLetter(CoordKvStore.Entry entry) {
        try {
            StoredDeadLetter dead = CoordJson.read(entry.value(), StoredDeadLetter.class);
            return new DeadLetter(
                    dead.id(),
                    dead.topic(),
                    dead.group(),
                    dead.message().toEventMessage(),
                    dead.failure(),
                    Instant.ofEpochMilli(dead.deadLetteredAtMs()));
        } catch (JacksonException e) {
            throw new EventBusException(
                    EventBusErrorCode.INTERNAL_ERROR,
                    "coord dead letter record is corrupt at " + endpoint,
                    e);
        }
    }

    private static byte[] json(Object value) {
        return CoordJson.write(value);
    }

    private EventBusException leaseLost() {
        return new EventBusException(
                EventBusErrorCode.LEASE_LOST, "delivery lease lost or already settled");
    }

    private void requireOpen() {
        if (closed) {
            throw new EventBusException(
                    EventBusErrorCode.PROVIDER_CLOSED,
                    "coord consumer store is closed at " + endpoint);
        }
    }

    /** 账本记录；{@code retryAfter} 与 {@code leaseUntil} 都由本地时钟判定。 */
    record Delivery(
            String group,
            String deliveryId,
            String topic,
            int partition,
            long offset,
            int attempts,
            long leaseUntilMs,
            long retryAfterMs) {

        boolean due(long now) {
            return now >= leaseUntilMs && now >= retryAfterMs;
        }

        Delivery claim(String nextDeliveryId, long now, long leaseMs) {
            return new Delivery(
                    group,
                    nextDeliveryId,
                    topic,
                    partition,
                    offset,
                    attempts + 1,
                    now + leaseMs,
                    0);
        }

        Delivery retry(long now, long delayMs) {
            return new Delivery(
                    group, deliveryId, topic, partition, offset, attempts, now, now + delayMs);
        }
    }

    private record Located(String key) {}

    private record GroupConsumer(Set<String> topics, MqV2Consumer consumer) {}
}
