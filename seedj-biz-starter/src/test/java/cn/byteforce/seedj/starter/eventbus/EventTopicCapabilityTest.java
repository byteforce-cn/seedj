package cn.byteforce.seedj.starter.eventbus;

import cn.byteforce.seedj.core.InfrastructureErrorCode;
import cn.byteforce.seedj.core.InfrastructureException;
import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.domain.event.EventType;
import cn.byteforce.seedj.eventbus.EventEnvelope;
import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.EventSubscriber;
import cn.byteforce.seedj.eventbus.EventSubscription;
import cn.byteforce.seedj.eventbus.spi.EventBusTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * topic 能力：订阅/配置声明的 topic 必须在启动期就存在。
 */
class EventTopicCapabilityTest {

    @Test
    @DisplayName("能力名与顺序固定")
    void exposesNameAndOrder() {
        EventTopicCapability capability = capability(new RecordingBus(), new EventBusProperties());

        assertThat(capability.getName()).isEqualTo(EventTopicCapability.NAME);
        assertThat(capability.getOrder()).isEqualTo(10);
    }

    @Test
    @DisplayName("topic 是配置 topic 与订阅 topic 的并集（去重、保持声明顺序）")
    void topicsMergeConfigurationAndSubscriptions() {
        EventBusProperties properties = new EventBusProperties();
        properties.setTopics(List.of("audit.trail", "order.paid"));
        EventTopicCapability capability = capability(new RecordingBus(), properties,
                new Subscriber("order.paid", "order.cancelled", "audit.trail"));

        assertThat(capability.topics())
                .containsExactly("audit.trail", "order.paid", "order.cancelled");
    }

    @Test
    @DisplayName("没有 transport 时能力视为就绪，初始化与校验都不做事")
    void noTransportIsReady() {
        EventTopicCapability capability = new EventTopicCapability(
                transportProvider(null),
                subscriberProvider(List.of(new Subscriber("order.paid"))),
                new EventBusProperties());

        assertThat(capability.isReady()).isTrue();
        capability.initialize();
        capability.validate();
    }

    @Test
    @DisplayName("初始化时逐个 ensureTopic")
    void initializeEnsuresEveryTopic() {
        RecordingBus bus = new RecordingBus();
        EventBusProperties properties = new EventBusProperties();
        properties.setTopics(List.of("audit.trail"));
        EventTopicCapability capability = capability(bus, properties,
                new Subscriber("order.paid"));

        capability.initialize();

        assertThat(bus.ensured).containsExactly("audit.trail", "order.paid");
    }

    @Test
    @DisplayName("topic 缺失时 isReady=false，validate 抛 NOT_READY 并列出 topic")
    void validateFailsWhenTopicMissing() {
        RecordingBus bus = new RecordingBus();
        bus.existing.add("order.paid");
        EventTopicCapability capability = capability(bus, new EventBusProperties(),
                new Subscriber("order.paid", "order.cancelled"));

        assertThat(capability.isReady()).isFalse();
        assertThatThrownBy(capability::validate)
                .isInstanceOfSatisfying(InfrastructureException.class, e -> {
                    assertThat(e.code()).isEqualTo(InfrastructureErrorCode.NOT_READY.name());
                    assertThat(e.getMessage()).contains("order.cancelled");
                });
    }

    @Test
    @DisplayName("topic 齐全时 isReady=true，validate 通过")
    void validatePassesWhenAllTopicsExist() {
        RecordingBus bus = new RecordingBus();
        bus.existing.addAll(Set.of("order.paid", "order.cancelled"));
        EventTopicCapability capability = capability(bus, new EventBusProperties(),
                new Subscriber("order.paid", "order.cancelled"));

        assertThat(capability.isReady()).isTrue();
        capability.validate();
        assertThat(bus.ensured).isEmpty();
    }

    private static EventTopicCapability capability(RecordingBus bus, EventBusProperties properties,
                                                   EventSubscriber<?>... subscribers) {
        return new EventTopicCapability(transportProvider(bus),
                subscriberProvider(List.of(subscribers)), properties);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<EventBusTransport> transportProvider(EventBusTransport transport) {
        ObjectProvider<EventBusTransport> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(transport);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<EventSubscriber<?>> subscriberProvider(
            List<EventSubscriber<?>> subscribers) {
        ObjectProvider<EventSubscriber<?>> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation -> subscribers.stream());
        return provider;
    }

    @EventType("test.topic.event")
    static final class TopicEvent implements DomainEvent {
    }

    static final class Subscriber implements EventSubscriber<TopicEvent> {

        private final Set<String> topics;

        Subscriber(String... topics) {
            this.topics = Set.of(topics);
        }

        @Override
        public EventSubscription subscription() {
            return EventSubscription.of("topics-test", topics, 1);
        }

        @Override
        public void onEvent(EventEnvelope<TopicEvent> envelope) {
        }
    }

    static final class RecordingBus implements EventBusTransport {

        final List<String> ensured = new ArrayList<>();
        final Set<String> existing = new java.util.LinkedHashSet<>();

        @Override
        public CompletionStage<Void> send(EventMessage message) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void ensureTopic(String topic) {
            ensured.add(topic);
            existing.add(topic);
        }

        @Override
        public boolean topicExists(String topic) {
            return existing.contains(topic);
        }
    }
}
