package cn.byteforce.seedj.starter.eventbus;

import cn.byteforce.seedj.core.InfrastructureException;
import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.domain.event.EventType;
import cn.byteforce.seedj.eventbus.EventBusErrorCode;
import cn.byteforce.seedj.eventbus.EventBusException;
import cn.byteforce.seedj.eventbus.EventEnvelope;
import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.EventSubscriber;
import cn.byteforce.seedj.eventbus.EventSubscription;
import cn.byteforce.seedj.eventbus.spi.DeliveryFailure;
import cn.byteforce.seedj.eventbus.spi.EventConsumerStore;
import cn.byteforce.seedj.eventbus.spi.EventPayloadSerializer;
import cn.byteforce.seedj.eventbus.spi.PollRequest;
import cn.byteforce.seedj.eventbus.spi.ReceivedEvent;
import cn.byteforce.seedj.starter.observability.TracePropagation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 消费容器生命周期与投递语义：启动探针、拉取、ack / retry / dead-letter、并发租约。
 *
 * <p>投递发生在容器自己的线程池里，因此断言统一"等待→观察 store 记录"，不靠 sleep 定时。
 */
class EventConsumerContainerTest {

    private final List<EventConsumerContainer> started = new ArrayList<>();

    @AfterEach
    void closeContainers() {
        started.forEach(EventConsumerContainer::close);
    }

    @Test
    @DisplayName("seedj.eventbus.consumer.enabled=false 时不注册调度、也不拉取")
    void disabledConsumerStaysIdle() {
        FakeStore store = new FakeStore();
        EventBusProperties properties = properties();
        properties.getConsumer().setEnabled(false);

        EventConsumerContainer container = start(stubs(store, serializer(), new EchoSubscriber()),
                properties);

        container.pollAll();

        assertThat(store.polls).isEmpty();
    }

    @Test
    @DisplayName("没有 EventConsumerStore 时静默空转，不阻断启动")
    void idleWithoutStore() {
        FakeStore store = new FakeStore();

        EventConsumerContainer container = start(
                stubs(null, serializer(), new EchoSubscriber()));

        container.pollAll();

        assertThat(store.polls).isEmpty();
    }

    @Test
    @DisplayName("缺 EventPayloadSerializer 时不消费（无法反序列化就不能 ack）")
    void idleWithoutSerializer() {
        FakeStore store = new FakeStore();

        EventConsumerContainer container = start(stubs(store, null, new EchoSubscriber()));

        container.pollAll();

        assertThat(store.polls).isEmpty();
    }

    @Test
    @DisplayName("没有订阅者时不消费")
    void idleWithoutSubscribers() {
        FakeStore store = new FakeStore();
        RecordingMetrics metrics = new RecordingMetrics();

        EventConsumerContainer container = start(new Stubs(store, serializer(), List.of(),
                metrics, TracePropagation.noop(), mock(Environment.class)));

        container.pollAll();

        assertThat(store.polls).isEmpty();
        assertThat(metrics.consumedCount).hasValue(0);
    }

    @Test
    @DisplayName("成功处理 → ack + consumed，追踪作用域覆盖整段投递")
    void dispatchesThenAcks() {
        FakeStore store = new FakeStore();
        store.enqueue(received("delivery-1", "billing", 1,
                Map.of("traceparent", "00-abc-def-01")));
        EchoSubscriber subscriber = new EchoSubscriber();
        RecordingMetrics metrics = new RecordingMetrics();
        RecordingTrace tracing = new RecordingTrace();

        EventConsumerContainer container = start(
                stubs(store, serializer(), subscriber, metrics, tracing));

        container.pollAll();

        awaitUntil(() -> store.acked.size() == 1);
        assertThat(store.acked).extracting(ReceivedEvent::deliveryId)
                .containsExactly("delivery-1");
        assertThat(store.retried).isEmpty();
        assertThat(store.deadLettered).isEmpty();
        assertThat(metrics.consumedCount).hasValue(1);

        EventEnvelope<SampleEvent> envelope = subscriber.received.get(0);
        assertThat(envelope.event().id()).isEqualTo("e-1");
        assertThat(envelope.eventId()).isEqualTo("e-1");
        assertThat(envelope.topic()).isEqualTo("test.consumer.event");
        assertThat(envelope.group()).isEqualTo("billing");
        assertThat(envelope.attempt()).isEqualTo(1);
        assertThat(envelope.occurredAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(envelope.headers()).containsEntry("traceparent", "00-abc-def-01");

        assertThat(tracing.extractedHeaders)
                .containsExactly(Map.of("traceparent", "00-abc-def-01"));
        assertThat(tracing.openScopes).hasValue(0);
    }

    @Test
    @DisplayName("订阅 group 为空 → 回退到 spring.application.name")
    void fallsBackToApplicationNameAsGroup() {
        FakeStore store = new FakeStore();
        Environment environment = mock(Environment.class);
        when(environment.getProperty("spring.application.name")).thenReturn("billing-service");

        EventConsumerContainer container = start(new Stubs(store, serializer(),
                new EchoSubscriber(null, "test.consumer.event"), EventBusMetrics.noop(),
                TracePropagation.noop(), environment));

        container.pollAll();

        assertThat(store.polls).hasSize(1);
        assertThat(store.polls.get(0).group()).isEqualTo("billing-service");
    }

    @Test
    @DisplayName("group 为空且没有应用名 → 回退到 seedj")
    void fallsBackToSeedjGroup() {
        FakeStore store = new FakeStore();
        Environment environment = mock(Environment.class);
        when(environment.getProperty("spring.application.name")).thenReturn("  ");

        EventConsumerContainer container = start(new Stubs(store, serializer(),
                new EchoSubscriber(null, "test.consumer.event"), EventBusMetrics.noop(),
                TracePropagation.noop(), environment));

        container.pollAll();

        assertThat(store.polls).hasSize(1);
        assertThat(store.polls.get(0).group()).isEqualTo("seedj");
    }

    @Test
    @DisplayName("拉取条数取 batch-size 与剩余并发额度的较小值，租约透传给 store")
    void pollLimitRespectsBatchSizeAndConcurrency() {
        FakeStore store = new FakeStore();
        EventBusProperties properties = properties();
        properties.getConsumer().setBatchSize(2);
        properties.getConsumer().setLease(Duration.ofSeconds(7));

        EventConsumerContainer container = start(stubs(store, serializer(),
                new EchoSubscriber("billing", "test.consumer.event", 5)), properties);

        container.pollAll();

        assertThat(store.polls).hasSize(1);
        PollRequest request = store.polls.get(0);
        assertThat(request.limit()).isEqualTo(2);
        assertThat(request.lease()).isEqualTo(Duration.ofSeconds(7));
        assertThat(request.topics()).containsExactly("test.consumer.event");
    }

    @Test
    @DisplayName("并发额度用尽时跳过该订阅，不再压 store")
    void skipsSubscriptionWithoutFreePermits() throws Exception {
        FakeStore store = new FakeStore();
        store.enqueue(received("delivery-1", "billing", 1, Map.of()));
        BlockingSubscriber subscriber = new BlockingSubscriber();

        EventConsumerContainer container = start(
                stubs(store, serializer(), subscriber));

        container.pollAll();
        assertThat(subscriber.started.await(5, TimeUnit.SECONDS)).isTrue();

        container.pollAll();
        assertThat(store.polls).hasSize(1);

        subscriber.release.countDown();
        awaitUntil(() -> store.acked.size() == 1);
    }

    @Test
    @DisplayName("处理异常且可重试、未超过 max-attempts → retry 带退避")
    void retriesRetryableFailure() {
        FakeStore store = new FakeStore();
        store.enqueue(received("delivery-1", "billing", 1, Map.of()));
        RecordingMetrics metrics = new RecordingMetrics();
        EventBusProperties properties = properties();
        properties.getConsumer().setRetryBase(Duration.ofMillis(250));
        properties.getConsumer().setRetryMax(Duration.ofSeconds(10));

        EventConsumerContainer container = start(stubs(store, serializer(),
                new FailingSubscriber(new InfrastructureException(
                        EventBusErrorCode.CONSUMER_FAILED, "backend down")),
                metrics), properties);

        container.pollAll();

        awaitUntil(() -> store.retried.size() == 1);
        assertThat(store.retryDelays).containsExactly(Duration.ofMillis(250));
        DeliveryFailure failure = store.failures.get(0);
        assertThat(failure.retryable()).isTrue();
        assertThat(failure.errorType()).isEqualTo(InfrastructureException.class.getName());
        assertThat(failure.errorMessage()).isEqualTo("backend down");
        assertThat(metrics.retriedCount).hasValue(1);
        assertThat(metrics.failedNonRetryableCount).hasValue(0);
        assertThat(store.acked).isEmpty();
        assertThat(store.deadLettered).isEmpty();
    }

    @Test
    @DisplayName("异步包装异常先解包，再按内部异常判定可重试性")
    void unwrapsAsyncWrapper() {
        FakeStore store = new FakeStore();
        store.enqueue(received("delivery-1", "billing", 1, Map.of()));

        EventConsumerContainer container = start(stubs(store, serializer(),
                new FailingSubscriber(new CompletionException(new InfrastructureException(
                        EventBusErrorCode.CONSUMER_FAILED, "backend down")))));

        container.pollAll();

        awaitUntil(() -> store.retried.size() == 1);
        DeliveryFailure failure = store.failures.get(0);
        assertThat(failure.errorType()).isEqualTo(InfrastructureException.class.getName());
        assertThat(failure.retryable()).isTrue();
    }

    @Test
    @DisplayName("退避按 2^(attempts-1) 翻倍并夹在 retry-max")
    void backoffDoublesAndClamps() {
        FakeStore store = new FakeStore();
        store.enqueue(received("delivery-1", "billing", 3, Map.of()));
        EventBusProperties properties = properties();
        properties.getConsumer().setMaxAttempts(10);
        properties.getConsumer().setRetryBase(Duration.ofMillis(100));
        properties.getConsumer().setRetryMax(Duration.ofMillis(300));

        EventConsumerContainer container = start(stubs(store, serializer(),
                new FailingSubscriber(new InfrastructureException(
                        EventBusErrorCode.CONSUMER_FAILED, "backend down"))), properties);

        container.pollAll();

        awaitUntil(() -> store.retryDelays.size() == 1);
        assertThat(store.retryDelays).containsExactly(Duration.ofMillis(300));
    }

    @Test
    @DisplayName("退避乘法溢出时退回 retry-max，而不是算术异常")
    void backoffOverflowFallsBackToMax() {
        FakeStore store = new FakeStore();
        store.enqueue(received("delivery-1", "billing", 2, Map.of()));
        EventBusProperties properties = properties();
        properties.getConsumer().setMaxAttempts(10);
        properties.getConsumer().setRetryBase(Duration.ofMillis(Long.MAX_VALUE / 2));
        properties.getConsumer().setRetryMax(Duration.ofSeconds(2));

        EventConsumerContainer container = start(stubs(store, serializer(),
                new FailingSubscriber(new InfrastructureException(
                        EventBusErrorCode.CONSUMER_FAILED, "backend down"))), properties);

        container.pollAll();

        awaitUntil(() -> store.retryDelays.size() == 1);
        assertThat(store.retryDelays).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("超过 max-attempts → 死信，不再 retry")
    void deadLettersWhenAttemptsExhausted() {
        FakeStore store = new FakeStore();
        store.enqueue(received("delivery-1", "billing", 5, Map.of()));
        RecordingMetrics metrics = new RecordingMetrics();

        EventConsumerContainer container = start(stubs(store, serializer(),
                new FailingSubscriber(new InfrastructureException(
                        EventBusErrorCode.CONSUMER_FAILED, "backend down")),
                metrics));

        container.pollAll();

        awaitUntil(() -> store.deadLettered.size() == 1);
        assertThat(store.retried).isEmpty();
        assertThat(metrics.deadLetteredCount).hasValue(1);
        assertThat(store.failures.get(0).retryable()).isTrue();
        assertThat(metrics.retriedCount).hasValue(0);
    }

    @Test
    @DisplayName("不可重试的 IllegalArgumentException → 直接死信")
    void deadLettersNonRetryableFailure() {
        FakeStore store = new FakeStore();
        store.enqueue(received("delivery-1", "billing", 1, Map.of()));

        EventConsumerContainer container = start(stubs(store, serializer(),
                new FailingSubscriber(new IllegalArgumentException("bad payload"))));

        container.pollAll();

        awaitUntil(() -> store.deadLettered.size() == 1);
        assertThat(store.failures.get(0).retryable()).isFalse();
        assertThat(store.failures.get(0).errorType())
                .isEqualTo(IllegalArgumentException.class.getName());
        assertThat(store.failures.get(0).errorMessage()).isEqualTo("bad payload");
        assertThat(store.retried).isEmpty();
    }

    @Test
    @DisplayName("反序列化结果类型不符 → 死信，而不是 ClassCastException 吞掉消息")
    void deadLettersMismatchedPayloadType() {
        FakeStore store = new FakeStore();
        store.enqueue(received("delivery-1", "billing", 1, Map.of()));
        EventPayloadSerializer wrongType = new EventPayloadSerializer() {
            @Override
            public byte[] serialize(Object payload) {
                return new byte[0];
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T deserialize(byte[] payload, Class<T> type) {
                return (T) "not an event";
            }
        };

        EventConsumerContainer container = start(
                stubs(store, wrongType, new EchoSubscriber()));

        container.pollAll();

        awaitUntil(() -> store.deadLettered.size() == 1);
        assertThat(store.failures.get(0).errorType())
                .isEqualTo(EventBusException.class.getName());
        assertThat(store.failures.get(0).errorMessage()).contains("but subscriber expects");
    }

    @Test
    @DisplayName("轮询失败计数后继续空转，不打断其他订阅")
    void pollFailureIsMeteredAndOtherSubscriptionsContinue() {
        FlakyStore store = new FlakyStore("broken");
        store.enqueue(received("delivery-1", "working", 1, Map.of()));
        RecordingMetrics metrics = new RecordingMetrics();

        EventConsumerContainer container = start(new Stubs(store, serializer(),
                List.of(new EchoSubscriber("working", "test.consumer.event"),
                        new EchoSubscriber("broken", "test.consumer.event")),
                metrics, TracePropagation.noop(), mock(Environment.class)));

        container.pollAll();

        awaitUntil(() -> store.acked.size() == 1);
        assertThat(store.polls).extracting(PollRequest::group)
                .containsExactlyInAnyOrder("working", "broken");
        assertThat(metrics.pollFailedCount).hasValue(1);
        assertThat(metrics.consumedCount).hasValue(1);
    }

    @Test
    @DisplayName("ack 失败（租约丢失）走失败路径；store 再失败只记日志，且信号量不泄漏")
    void ackFailureDoesNotLeakPermits() {
        FakeStore store = new FakeStore();
        store.enqueue(received("delivery-1", "billing", 1, Map.of()));
        store.ackError = new EventBusException(EventBusErrorCode.LEASE_LOST, "lease lost");
        store.deadLetterError = new IllegalStateException("store down");
        RecordingMetrics metrics = new RecordingMetrics();

        EventConsumerContainer container = start(
                stubs(store, serializer(), new EchoSubscriber(), metrics));

        container.pollAll();

        awaitUntil(() -> store.ackAttempts.get() == 1);
        awaitUntil(() -> store.deadLetterAttempts.get() == 1);

        // 信号量必须归还：租约到期后消息会被重新领取，仍能再次投递。
        // 上一轮可能仍占着额度，因此反复驱动 pollAll 直到重新领取到。
        store.enqueue(received("delivery-2", "billing", 1, Map.of()));
        awaitUntil(() -> {
            container.pollAll();
            return store.ackAttempts.get() == 2;
        });
        assertThat(metrics.consumedCount).hasValue(0);
        assertThat(metrics.deadLetteredCount).hasValue(0);
    }

    @Test
    @DisplayName("多个订阅各自按 group/topic 拉取")
    void pollsEverySubscription() {
        FakeStore store = new FakeStore();

        EventConsumerContainer container = start(new Stubs(store, serializer(),
                List.of(new EchoSubscriber("billing", "order.paid"),
                        new EchoSubscriber("search", "order.paid", "order.shipped")),
                EventBusMetrics.noop(), TracePropagation.noop(), mock(Environment.class)));

        container.pollAll();

        assertThat(store.polls).hasSize(2);
        assertThat(store.polls).extracting(PollRequest::group)
                .containsExactlyInAnyOrder("billing", "search");
        assertThat(store.polls).filteredOn(request -> request.group().equals("search"))
                .singleElement()
                .satisfies(request -> assertThat(request.topics())
                        .containsExactlyInAnyOrder("order.paid", "order.shipped"));
    }

    @Test
    @DisplayName("close() 幂等，并在关闭后停止轮询")
    void closeIsIdempotentAndStopsPolling() {
        FakeStore store = new FakeStore();

        EventConsumerContainer container = start(
                stubs(store, serializer(), new EchoSubscriber()));

        container.close();
        container.close();
        container.pollAll();

        assertThat(store.polls).isEmpty();
    }

    @Test
    @DisplayName("未启动就 close() 不抛异常（调度器/线程池尚未创建）")
    void closeBeforeStartIsSafe() {
        EventConsumerContainer container = new EventConsumerContainer(
                storeProvider(null),
                subscriberProvider(List.of()),
                serializerProvider(null),
                properties(), EventBusMetrics.noop(), TracePropagation.noop(),
                mock(Environment.class));

        assertThatCode(container::close).doesNotThrowAnyException();
        assertThatCode(container::close).doesNotThrowAnyException();
    }

    private EventConsumerContainer start(Stubs stubs) {
        return start(stubs, properties());
    }

    private EventConsumerContainer start(Stubs stubs, EventBusProperties properties) {
        EventConsumerContainer container = new EventConsumerContainer(
                storeProvider(stubs.stores), subscriberProvider(stubs.subscribers),
                serializerProvider(stubs.serializer),
                properties,
                stubs.metrics == null ? EventBusMetrics.noop() : stubs.metrics,
                stubs.tracing == null ? TracePropagation.noop() : stubs.tracing,
                stubs.environment());
        container.afterPropertiesSet();
        started.add(container);
        return container;
    }

    private static Stubs stubs(EventConsumerStore store, EventPayloadSerializer serializer,
                               EventSubscriber<?> subscriber) {
        return new Stubs(store, serializer, subscriber, EventBusMetrics.noop(),
                TracePropagation.noop(), mock(Environment.class));
    }

    private static Stubs stubs(EventConsumerStore store, EventPayloadSerializer serializer,
                               EventSubscriber<?> subscriber, EventBusMetrics metrics) {
        return new Stubs(store, serializer, subscriber, metrics, TracePropagation.noop(),
                mock(Environment.class));
    }

    private static Stubs stubs(EventConsumerStore store, EventPayloadSerializer serializer,
                               EventSubscriber<?> subscriber, EventBusMetrics metrics,
                               TracePropagation tracing) {
        return new Stubs(store, serializer, subscriber, metrics, tracing,
                mock(Environment.class));
    }

    private static EventBusProperties properties() {
        EventBusProperties properties = new EventBusProperties();
        // 调度线程保持静默：测试自行驱动 pollAll()，结果才可判定。
        properties.getConsumer().setPollInterval(Duration.ofHours(1));
        return properties;
    }

    private static ReceivedEvent received(String deliveryId, String group, int attempts,
                                          Map<String, String> headers) {
        EventMessage message = new EventMessage("e-1", "test.consumer.event", "k-1",
                "test.consumer.event", 1,
                "{\"id\":\"e-1\"}".getBytes(StandardCharsets.UTF_8), headers,
                Instant.parse("2026-01-01T00:00:00Z"));
        return new ReceivedEvent(deliveryId, group, message, attempts,
                Instant.parse("2026-01-01T00:01:00Z"));
    }

    private static EventPayloadSerializer serializer() {
        return new EventPayloadSerializer() {
            @Override
            public byte[] serialize(Object payload) {
                return "{}".getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public <T> T deserialize(byte[] payload, Class<T> type) {
                return type.cast(new SampleEvent("e-1"));
            }
        };
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for consumer dispatch");
            }
        }
        fail("consumer dispatch did not happen within 5s");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<EventConsumerStore> storeProvider(EventConsumerStore store) {
        ObjectProvider<EventConsumerStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<EventPayloadSerializer> serializerProvider(
            EventPayloadSerializer serializer) {
        ObjectProvider<EventPayloadSerializer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(serializer);
        return provider;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ObjectProvider<EventSubscriber<?>> subscriberProvider(
            List<EventSubscriber<?>> subscribers) {
        ObjectProvider<EventSubscriber<?>> provider = mock(ObjectProvider.class);
        if (!subscribers.isEmpty()) {
            when(provider.getIfAvailable()).thenReturn((EventSubscriber) subscribers.get(0));
        }
        when(provider.orderedStream()).thenAnswer(invocation -> subscribers.stream());
        return provider;
    }

    @EventType("test.consumer.event")
    static final class SampleEvent implements DomainEvent {

        private final String id;

        SampleEvent(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    static class EchoSubscriber implements EventSubscriber<SampleEvent> {

        private final String group;
        private final Set<String> topics;
        private final int concurrency;
        final List<EventEnvelope<SampleEvent>> received = new CopyOnWriteArrayList<>();

        EchoSubscriber() {
            this("billing", Set.of("test.consumer.event"), 1);
        }

        EchoSubscriber(String group, String... topics) {
            this(group, Set.of(topics), 1);
        }

        EchoSubscriber(String group, String topic, int concurrency) {
            this(group, Set.of(topic), concurrency);
        }

        private EchoSubscriber(String group, Set<String> topics, int concurrency) {
            this.group = group;
            this.topics = topics;
            this.concurrency = concurrency;
        }

        @Override
        public EventSubscription subscription() {
            return EventSubscription.of(group, topics, concurrency);
        }

        @Override
        public void onEvent(EventEnvelope<SampleEvent> envelope) {
            received.add(envelope);
        }
    }

    static final class BlockingSubscriber implements EventSubscriber<SampleEvent> {

        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public EventSubscription subscription() {
            return EventSubscription.of("billing", "test.consumer.event");
        }

        @Override
        public void onEvent(EventEnvelope<SampleEvent> envelope) {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static final class FailingSubscriber implements EventSubscriber<SampleEvent> {

        private final RuntimeException failure;

        FailingSubscriber(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public EventSubscription subscription() {
            return EventSubscription.of("billing", "test.consumer.event");
        }

        @Override
        public void onEvent(EventEnvelope<SampleEvent> envelope) {
            throw failure;
        }
    }

    static final class RecordingMetrics implements EventBusMetrics {

        final AtomicInteger consumedCount = new AtomicInteger();
        final AtomicInteger retriedCount = new AtomicInteger();
        final AtomicInteger failedNonRetryableCount = new AtomicInteger();
        final AtomicInteger deadLetteredCount = new AtomicInteger();
        final AtomicInteger pollFailedCount = new AtomicInteger();

        @Override
        public void consumed() {
            consumedCount.incrementAndGet();
        }

        @Override
        public void consumerFailed(boolean retryable) {
            (retryable ? retriedCount : failedNonRetryableCount).incrementAndGet();
        }

        @Override
        public void deadLettered() {
            deadLetteredCount.incrementAndGet();
        }

        @Override
        public void pollFailed() {
            pollFailedCount.incrementAndGet();
        }
    }

    static final class RecordingTrace implements TracePropagation {

        final List<Map<String, String>> extractedHeaders = new CopyOnWriteArrayList<>();
        final AtomicInteger openScopes = new AtomicInteger();

        @Override
        public Map<String, String> inject(Map<String, String> headers) {
            return headers;
        }

        @Override
        public Scope extract(Map<String, String> headers) {
            extractedHeaders.add(headers);
            openScopes.incrementAndGet();
            return openScopes::decrementAndGet;
        }
    }

    /**
     * 组件集合：容器构造参数的可选/多值形态都在这里表达。
     */
    static final class Stubs {

        final EventConsumerStore stores;
        final EventPayloadSerializer serializer;
        final List<EventSubscriber<?>> subscribers;
        final EventBusMetrics metrics;
        final TracePropagation tracing;
        private final Environment environment;

        Stubs(EventConsumerStore store, EventPayloadSerializer serializer,
              EventSubscriber<?> subscriber, EventBusMetrics metrics, TracePropagation tracing,
              Environment environment) {
            this(store, serializer,
                    subscriber == null ? List.of() : List.of(subscriber),
                    metrics, tracing, environment);
        }

        Stubs(EventConsumerStore store, EventPayloadSerializer serializer,
              List<EventSubscriber<?>> subscribers, EventBusMetrics metrics,
              TracePropagation tracing, Environment environment) {
            this.stores = store;
            this.serializer = serializer;
            this.subscribers = subscribers;
            this.metrics = metrics;
            this.tracing = tracing;
            this.environment = environment;
        }

        Environment environment() {
            return environment;
        }
    }

    /**
     * 指定 group 的轮询会抛错，其余 group 正常投递。
     */
    static final class FlakyStore implements EventConsumerStore {

        private final String failingGroup;
        private final ConcurrentLinkedQueue<ReceivedEvent> queue = new ConcurrentLinkedQueue<>();
        final List<PollRequest> polls = new CopyOnWriteArrayList<>();
        final List<ReceivedEvent> acked = new CopyOnWriteArrayList<>();

        FlakyStore(String failingGroup) {
            this.failingGroup = failingGroup;
        }

        void enqueue(ReceivedEvent event) {
            queue.add(event);
        }

        @Override
        public List<ReceivedEvent> poll(PollRequest request) {
            polls.add(request);
            if (failingGroup.equals(request.group())) {
                throw new IllegalStateException("store down");
            }
            List<ReceivedEvent> batch = new ArrayList<>();
            for (int i = 0; i < request.limit(); i++) {
                ReceivedEvent next = queue.poll();
                if (next == null) {
                    break;
                }
                batch.add(next);
            }
            return batch;
        }

        @Override
        public void ack(ReceivedEvent event) {
            acked.add(event);
        }

        @Override
        public void retry(ReceivedEvent event, Duration delay, DeliveryFailure failure) {
        }

        @Override
        public void deadLetter(ReceivedEvent event, DeliveryFailure failure) {
        }
    }

    static final class FakeStore implements EventConsumerStore {

        final List<PollRequest> polls = new CopyOnWriteArrayList<>();
        final List<ReceivedEvent> acked = new CopyOnWriteArrayList<>();
        final List<ReceivedEvent> retried = new CopyOnWriteArrayList<>();
        final List<Duration> retryDelays = new CopyOnWriteArrayList<>();
        final List<DeliveryFailure> failures = new CopyOnWriteArrayList<>();
        final List<ReceivedEvent> deadLettered = new CopyOnWriteArrayList<>();
        final AtomicInteger ackAttempts = new AtomicInteger();
        final AtomicInteger deadLetterAttempts = new AtomicInteger();
        private final ConcurrentLinkedQueue<ReceivedEvent> queue = new ConcurrentLinkedQueue<>();

        RuntimeException pollError;
        RuntimeException ackError;
        RuntimeException deadLetterError;

        void enqueue(ReceivedEvent event) {
            queue.add(event);
        }

        @Override
        public List<ReceivedEvent> poll(PollRequest request) {
            polls.add(request);
            if (pollError != null) {
                throw pollError;
            }
            List<ReceivedEvent> batch = new ArrayList<>();
            for (int i = 0; i < request.limit(); i++) {
                ReceivedEvent next = queue.poll();
                if (next == null) {
                    break;
                }
                batch.add(next);
            }
            return batch;
        }

        @Override
        public void ack(ReceivedEvent event) {
            ackAttempts.incrementAndGet();
            if (ackError != null) {
                throw ackError;
            }
            acked.add(event);
        }

        @Override
        public void retry(ReceivedEvent event, Duration delay, DeliveryFailure failure) {
            retried.add(event);
            retryDelays.add(delay);
            failures.add(failure);
        }

        @Override
        public void deadLetter(ReceivedEvent event, DeliveryFailure failure) {
            deadLetterAttempts.incrementAndGet();
            if (deadLetterError != null) {
                throw deadLetterError;
            }
            deadLettered.add(event);
            failures.add(failure);
        }
    }
}
