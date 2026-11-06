package cn.byteforce.seedj.starter.eventbus;

import cn.byteforce.seedj.core.InfrastructureException;
import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.eventbus.EventBusErrorCode;
import cn.byteforce.seedj.eventbus.EventBusException;
import cn.byteforce.seedj.eventbus.EventEnvelope;
import cn.byteforce.seedj.eventbus.EventSubscriber;
import cn.byteforce.seedj.eventbus.EventSubscription;
import cn.byteforce.seedj.eventbus.spi.EventConsumerStore;
import cn.byteforce.seedj.eventbus.spi.EventPayloadSerializer;
import cn.byteforce.seedj.eventbus.spi.DeliveryFailure;
import cn.byteforce.seedj.eventbus.spi.PollRequest;
import cn.byteforce.seedj.eventbus.spi.ReceivedEvent;
import cn.byteforce.seedj.starter.observability.TracePropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 消费者容器：发现 {@link EventSubscriber} bean，按订阅轮询
 * {@link EventConsumerStore}，反序列化后调用业务处理器。
 *
 * <p>投递语义：
 * <ul>
 *   <li>正常返回 → ack；</li>
 *   <li>异常且可重试且未超过 max-attempts → retry（带退避）；</li>
 *   <li>不可重试或超过 max-attempts → dead-letter；</li>
 *   <li>没有 store / serializer 时只打日志并空转，不阻断启动。</li>
 * </ul>
 *
 * <p>自己持有调度线程：业务应用不需要 {@code @EnableScheduling}。
 */
public class EventConsumerContainer implements InitializingBean, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EventConsumerContainer.class);

    private final ObjectProvider<EventConsumerStore> stores;
    private final ObjectProvider<EventSubscriber<?>> subscribers;
    private final ObjectProvider<EventPayloadSerializer> serializers;
    private final EventBusProperties properties;
    private final EventBusMetrics metrics;
    private final TracePropagation tracing;
    private final Environment environment;

    private final List<SubscriptionRuntime> runtimes = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private ScheduledExecutorService scheduler;
    private ExecutorService workers;
    private volatile EventConsumerStore store;
    private volatile EventPayloadSerializer serializer;

    public EventConsumerContainer(ObjectProvider<EventConsumerStore> stores,
                                  ObjectProvider<EventSubscriber<?>> subscribers,
                                  ObjectProvider<EventPayloadSerializer> serializers,
                                  EventBusProperties properties,
                                  EventBusMetrics metrics,
                                  TracePropagation tracing,
                                  Environment environment) {
        this.stores = stores;
        this.subscribers = subscribers;
        this.serializers = serializers;
        this.properties = properties;
        this.metrics = metrics;
        this.tracing = tracing;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.getConsumer().isEnabled()) {
            log.info("seedj eventbus consumer disabled by configuration");
            return;
        }
        this.store = stores.getIfAvailable();
        if (store == null) {
            log.info("seedj eventbus consumer idle: no EventConsumerStore on the classpath");
            return;
        }
        this.serializer = serializers.getIfAvailable();
        if (serializer == null) {
            log.warn("seedj eventbus consumer idle: no EventPayloadSerializer bean");
            return;
        }

        List<EventSubscriber<?>> discovered = subscribers.orderedStream().toList();
        if (discovered.isEmpty()) {
            log.info("seedj eventbus consumer idle: no EventSubscriber bean");
            return;
        }

        int workerCount = 0;
        for (EventSubscriber<?> subscriber : discovered) {
            EventSubscription subscription = subscriber.subscription();
            String group = resolveGroup(subscription);
            Class<? extends DomainEvent> eventType = resolveEventType(subscriber.getClass());
            runtimes.add(new SubscriptionRuntime(subscriber, eventType, group,
                    subscription.topics(), subscription.concurrency()));
            workerCount += subscription.concurrency();
        }

        this.workers = Executors.newFixedThreadPool(Math.max(1, workerCount),
                daemon("seedj-eventbus-consumer-worker"));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                daemon("seedj-eventbus-consumer"));

        long interval = Math.max(50, properties.getConsumer().getPollInterval().toMillis());
        scheduler.scheduleWithFixedDelay(this::pollAll, interval, interval, TimeUnit.MILLISECONDS);
        log.info("seedj eventbus consumer started: subscriptions={} workers={} poll={}ms",
                runtimes.size(), workerCount, interval);
    }

    /**
     * 执行一轮拉取。public 以便测试直接驱动。
     */
    public void pollAll() {
        if (closed.get() || store == null || serializer == null) {
            return;
        }
        for (SubscriptionRuntime runtime : runtimes) {
            if (closed.get()) {
                return;
            }
            int available = runtime.permits.availablePermits();
            if (available <= 0) {
                continue;
            }
            int limit = Math.min(properties.getConsumer().getBatchSize(), available);
            List<ReceivedEvent> events;
            try {
                events = store.poll(new PollRequest(runtime.group, runtime.topics, limit,
                        properties.getConsumer().getLease()));
            } catch (RuntimeException e) {
                metrics.pollFailed();
                log.error("seedj eventbus consumer poll failed for group {}", runtime.group, e);
                continue;
            }
            for (ReceivedEvent event : events) {
                if (!runtime.permits.tryAcquire()) {
                    break;
                }
                workers.execute(() -> dispatch(runtime, event));
            }
        }
    }

    private void dispatch(SubscriptionRuntime runtime, ReceivedEvent received) {
        // 在消费线程上恢复发布端上下文；作用域覆盖反序列化、业务处理与 ack/retry/deadLetter。
        try (TracePropagation.Scope ignored = tracing.extract(received.message().headers())) {
            dispatchInScope(runtime, received);
        }
    }

    private void dispatchInScope(SubscriptionRuntime runtime, ReceivedEvent received) {
        try {
            Object payload = serializer.deserialize(received.message().payload(),
                    runtime.eventType);
            if (!runtime.eventType.isInstance(payload)) {
                throw new EventBusException(EventBusErrorCode.SERIALIZATION_FAILED,
                        "payload deserialized to " + payload.getClass().getName()
                                + " but subscriber expects " + runtime.eventType.getName());
            }
            EventEnvelope<?> envelope = new EventEnvelope<>(
                    (DomainEvent) payload,
                    received.message().eventId(),
                    received.message().topic(),
                    runtime.group,
                    received.attempts(),
                    received.message().occurredAt(),
                    received.message().headers());
            runtime.invoke(envelope);
            store.ack(received);
            metrics.consumed();
        } catch (RuntimeException e) {
            onFailure(runtime, received, e);
        } finally {
            runtime.permits.release();
        }
    }

    private void onFailure(SubscriptionRuntime runtime, ReceivedEvent received, RuntimeException raw) {
        Throwable error = unwrap(raw);
        boolean retryable = isRetryable(error);
        boolean exhausted = received.attempts() >= properties.getConsumer().getMaxAttempts();
        DeliveryFailure failure = new DeliveryFailure(
                error.getClass().getName(), error.getMessage(), retryable);
        try {
            if (!retryable || exhausted) {
                store.deadLetter(received, failure);
                metrics.deadLettered();
                log.warn("dead-lettered event {} (group={}, attempts={}): {}",
                        received.message().eventId(), runtime.group, received.attempts(),
                        error.getMessage());
                return;
            }
            Duration delay = backoff(received.attempts());
            store.retry(received, delay, failure);
            metrics.consumerFailed(true);
            log.warn("retry event {} (group={}, attempts={}) in {}ms: {}",
                    received.message().eventId(), runtime.group, received.attempts(),
                    delay.toMillis(), error.getMessage());
        } catch (RuntimeException storeError) {
            log.error("cannot record consumer failure for event {}",
                    received.message().eventId(), storeError);
        }
    }

    private String resolveGroup(EventSubscription subscription) {
        if (subscription.group() != null && !subscription.group().isBlank()) {
            return subscription.group();
        }
        String applicationName = environment.getProperty("spring.application.name");
        return applicationName == null || applicationName.isBlank() ? "seedj" : applicationName;
    }

    private Duration backoff(int attempts) {
        Duration base = properties.getConsumer().getRetryBase();
        Duration max = properties.getConsumer().getRetryMax();
        if (attempts <= 0) {
            return base;
        }
        long multiplier = 1L << Math.min(attempts - 1, 20);
        long millis;
        try {
            millis = Math.multiplyExact(base.toMillis(), multiplier);
        } catch (ArithmeticException e) {
            return max;
        }
        return Duration.ofMillis(Math.min(millis, max.toMillis()));
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean isRetryable(Throwable error) {
        if (error instanceof InfrastructureException infrastructure) {
            return infrastructure.isRetryable();
        }
        return !(error instanceof IllegalArgumentException);
    }

    /**
     * 从 {@code EventSubscriber<E>} 解析具体事件类型；支持父类/接口间接实现。
     */
    static Class<? extends DomainEvent> resolveEventType(Class<?> subscriberClass) {
        for (Class<?> type = subscriberClass; type != null && type != Object.class;
             type = type.getSuperclass()) {
            Class<? extends DomainEvent> found = familyIn(type.getGenericInterfaces());
            if (found == null) {
                found = familyInSuperclass(type.getGenericSuperclass());
            }
            if (found != null) {
                return found;
            }
        }
        throw new EventBusException(EventBusErrorCode.INVALID_SUBSCRIBER,
                "cannot resolve event type from " + subscriberClass.getName()
                        + "; implement EventSubscriber<ConcreteEvent> with a concrete type");
    }

    /**
     * 从 {@code abstract class Base<E extends DomainEvent> implements EventSubscriber<E>}
     * 的具体子类中解析事件类型：类型实参挂在父类上，而不是 {@code getGenericInterfaces()}。
     */
    private static Class<? extends DomainEvent> familyInSuperclass(Type superclass) {
        if (!(superclass instanceof ParameterizedType parameterized)) {
            return null;
        }
        Class<?> raw = rawClass(parameterized);
        if (raw == null || !EventSubscriber.class.isAssignableFrom(raw)) {
            return null;
        }
        Type argument = parameterized.getActualTypeArguments()[0];
        if (argument instanceof Class<?> eventType
                && DomainEvent.class.isAssignableFrom(eventType)) {
            @SuppressWarnings("unchecked")
            Class<? extends DomainEvent> result = (Class<? extends DomainEvent>) eventType;
            return result;
        }
        return null;
    }

    private static Class<? extends DomainEvent> familyIn(Type[] interfaces) {
        for (Type candidate : interfaces) {
            Class<?> raw = rawClass(candidate);
            if (raw == null) {
                continue;
            }
            if (raw == EventSubscriber.class && candidate instanceof ParameterizedType parameterized) {
                Type argument = parameterized.getActualTypeArguments()[0];
                if (argument instanceof Class<?> eventType
                        && DomainEvent.class.isAssignableFrom(eventType)) {
                    @SuppressWarnings("unchecked")
                    Class<? extends DomainEvent> result = (Class<? extends DomainEvent>) eventType;
                    return result;
                }
            }
            Class<? extends DomainEvent> nested = familyIn(raw.getGenericInterfaces());
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized
                && parameterized.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        return null;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (workers != null) {
            workers.shutdown();
            try {
                if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                    workers.shutdownNow();
                }
            } catch (InterruptedException e) {
                workers.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("seedj eventbus consumer stopped");
    }

    private static java.util.concurrent.ThreadFactory daemon(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class SubscriptionRuntime {

        private final EventSubscriber<?> subscriber;
        private final Class<? extends DomainEvent> eventType;
        private final String group;
        private final Set<String> topics;
        private final Semaphore permits;

        private SubscriptionRuntime(EventSubscriber<?> subscriber,
                                    Class<? extends DomainEvent> eventType,
                                    String group,
                                    Set<String> topics,
                                    int concurrency) {
            this.subscriber = subscriber;
            this.eventType = eventType;
            this.group = group;
            this.topics = topics;
            this.permits = new Semaphore(concurrency);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private void invoke(EventEnvelope<?> envelope) {
            ((EventSubscriber) subscriber).onEvent(envelope);
        }
    }
}
