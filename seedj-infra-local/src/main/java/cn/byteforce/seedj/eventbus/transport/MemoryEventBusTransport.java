package cn.byteforce.seedj.eventbus.transport;

import cn.byteforce.seedj.eventbus.EventBusErrorCode;
import cn.byteforce.seedj.eventbus.EventBusException;
import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.spi.DeadLetter;
import cn.byteforce.seedj.eventbus.spi.EventBusTransport;
import cn.byteforce.seedj.eventbus.spi.EventConsumerStore;
import cn.byteforce.seedj.eventbus.spi.DeliveryFailure;
import cn.byteforce.seedj.eventbus.spi.PollRequest;
import cn.byteforce.seedj.eventbus.spi.ReceivedEvent;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.id.LocalStringIdGenerator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内队列（有界，发布 + 消费组）。
 *
 * <p>写满后丢弃最旧记录并计数，避免本地开发进程 OOM。消费组状态保存在进程内，
 * 因此只适用于单进程测试/演示；跨进程或同机多实例请使用
 * {@link FileEventBusTransport} 或真正的 MQ。
 */
public class MemoryEventBusTransport implements EventBusTransport, EventConsumerStore {

    private final int capacity;
    private final IdGenerator<String> idGenerator;
    private final List<Entry> messages = new ArrayList<>();
    private final Set<String> topics = new LinkedHashSet<>();
    private final List<DeadLetter> deadLetters = new ArrayList<>();
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean closed;

    public MemoryEventBusTransport(int capacity) {
        this(capacity, new LocalStringIdGenerator());
    }

    public MemoryEventBusTransport(int capacity, IdGenerator<String> idGenerator) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity > 0");
        }
        this.capacity = capacity;
        this.idGenerator = idGenerator;
    }

    @Override
    public CompletionStage<Void> send(EventMessage message) {
        if (closed) {
            return CompletableFuture.failedFuture(new EventBusException(
                    EventBusErrorCode.PROVIDER_CLOSED, "transport is closed"));
        }
        synchronized (messages) {
            topics.add(message.topic());
            Entry existing = find(message.eventId());
            if (existing != null) {
                existing.message = message;
                return CompletableFuture.completedFuture(null);
            }
            messages.add(new Entry(message));
            while (messages.size() > capacity) {
                messages.remove(0);
                dropped.incrementAndGet();
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void ensureTopic(String topic) {
        synchronized (messages) {
            topics.add(topic);
        }
    }

    @Override
    public boolean topicExists(String topic) {
        synchronized (messages) {
            return topics.contains(topic);
        }
    }

    @Override
    public List<ReceivedEvent> poll(PollRequest request) {
        requireOpen();
        Instant now = Instant.now();
        List<ReceivedEvent> result = new ArrayList<>();
        synchronized (messages) {
            for (Entry entry : messages) {
                if (result.size() >= request.limit()) {
                    break;
                }
                if (!request.topics().contains(entry.message.topic())) {
                    continue;
                }
                GroupState state = entry.groups.computeIfAbsent(
                        request.group(), ignored -> new GroupState());
                if (state.acked || state.dead) {
                    continue;
                }
                if (state.leaseUntil != null && state.leaseUntil.isAfter(now)) {
                    continue;
                }
                state.attempts++;
                state.deliveryId = idGenerator.id();
                state.leaseUntil = now.plus(request.lease());
                result.add(new ReceivedEvent(state.deliveryId, request.group(),
                        entry.message, state.attempts, state.leaseUntil));
            }
        }
        return result;
    }

    @Override
    public void ack(ReceivedEvent event) {
        requireOpen();
        synchronized (messages) {
            Entry entry = find(event.message().eventId());
            if (entry == null) {
                throw leaseLost("ack", event);
            }
            GroupState state = entry.groups.get(event.group());
            if (state != null && state.acked) {
                return;
            }
            if (state == null || !event.deliveryId().equals(state.deliveryId)) {
                throw leaseLost("ack", event);
            }
            state.acked = true;
            state.deliveryId = null;
            state.leaseUntil = null;
        }
    }

    @Override
    public void retry(ReceivedEvent event, Duration delay, DeliveryFailure failure) {
        requireOpen();
        synchronized (messages) {
            Entry entry = find(event.message().eventId());
            GroupState state = entry == null ? null : entry.groups.get(event.group());
            if (state == null || !event.deliveryId().equals(state.deliveryId)) {
                throw leaseLost("retry", event);
            }
            state.deliveryId = null;
            state.leaseUntil = delay == null || delay.isZero() || delay.isNegative()
                    ? null : Instant.now().plus(delay);
        }
    }

    @Override
    public void deadLetter(ReceivedEvent event, DeliveryFailure failure) {
        requireOpen();
        synchronized (messages) {
            Entry entry = find(event.message().eventId());
            GroupState state = entry == null ? null : entry.groups.get(event.group());
            if (state == null || !event.deliveryId().equals(state.deliveryId)) {
                throw leaseLost("dead-letter", event);
            }
            state.dead = true;
            state.deliveryId = null;
            state.leaseUntil = null;
            deadLetters.add(new DeadLetter(idGenerator.id(),
                    entry.message.topic(), event.group(), entry.message, failure, Instant.now()));
        }
    }

    @Override
    public List<DeadLetter> deadLetters(String topic, String group, int limit) {
        synchronized (messages) {
            return deadLetters.stream()
                    .filter(deadLetter -> deadLetter.topic().equals(topic)
                            && deadLetter.group().equals(group))
                    .limit(Math.max(0, limit))
                    .toList();
        }
    }

    /** 当前缓冲区内容（测试与本地诊断用）。 */
    public List<EventMessage> snapshot() {
        synchronized (messages) {
            return messages.stream().map(entry -> entry.message).toList();
        }
    }

    public int size() {
        synchronized (messages) {
            return messages.size();
        }
    }

    public long droppedCount() {
        return dropped.get();
    }

    @Override
    public void close() {
        closed = true;
    }

    private void requireOpen() {
        if (closed) {
            throw new EventBusException(EventBusErrorCode.PROVIDER_CLOSED, "consumer is closed");
        }
    }

    private Entry find(String eventId) {
        for (Entry entry : messages) {
            if (entry.message.eventId().equals(eventId)) {
                return entry;
            }
        }
        return null;
    }

    private static EventBusException leaseLost(String action, ReceivedEvent event) {
        return new EventBusException(EventBusErrorCode.LEASE_LOST,
                "lease lost, cannot " + action + ": " + event.message().eventId());
    }

    private static final class Entry {
        private EventMessage message;
        private final Map<String, GroupState> groups = new HashMap<>();

        private Entry(EventMessage message) {
            this.message = message;
        }
    }

    private static final class GroupState {
        private String deliveryId;
        private Instant leaseUntil;
        private int attempts;
        private boolean acked;
        private boolean dead;
    }
}
