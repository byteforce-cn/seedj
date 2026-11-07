package cn.byteforce.seedj.starter.eventbus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.domain.event.EventType;
import cn.byteforce.seedj.eventbus.EventBusErrorCode;
import cn.byteforce.seedj.eventbus.EventBusException;
import cn.byteforce.seedj.eventbus.EventEnvelope;
import cn.byteforce.seedj.eventbus.EventSubscriber;
import cn.byteforce.seedj.eventbus.EventSubscription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 订阅者事件类型解析。
 *
 * <p>除了"直接实现 {@code EventSubscriber<E>}"，还必须支持通过抽象基类间接实现—— core 的 {@code AbstractProjection<E>}
 * 就是这个形状：类型实参挂在父类上， 只读 {@code getGenericInterfaces()} 会解析失败，消费容器启动时才会炸。
 */
class EventSubscriberTypeResolutionTest {

    @Test
    @DisplayName("直接实现 EventSubscriber<具体事件>")
    void resolvesDirectImplementation() {
        assertThat(EventConsumerContainer.resolveEventType(DirectSubscriber.class))
                .isEqualTo(TestEvent.class);
    }

    @Test
    @DisplayName("经抽象基类间接实现：类型实参在父类上")
    void resolvesInheritedGenericSuperclass() {
        assertThat(EventConsumerContainer.resolveEventType(InheritedSubscriber.class))
                .isEqualTo(TestEvent.class);
    }

    @Test
    @DisplayName("无法解析具体事件类型时启动期失败，而不是静默跳过")
    void rejectsUnresolvableSubscriber() {
        assertThatThrownBy(() -> EventConsumerContainer.resolveEventType(RawSubscriber.class))
                .isInstanceOfSatisfying(
                        EventBusException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(EventBusErrorCode.INVALID_SUBSCRIBER));
    }

    @EventType("test.subscriber.event")
    record TestEvent(String id) implements DomainEvent {}

    static final class DirectSubscriber implements EventSubscriber<TestEvent> {
        @Override
        public EventSubscription subscription() {
            return EventSubscription.of("test-direct", "test.subscriber.event");
        }

        @Override
        public void onEvent(EventEnvelope<TestEvent> envelope) {}
    }

    abstract static class BaseSubscriber<E extends DomainEvent> implements EventSubscriber<E> {
        @Override
        public EventSubscription subscription() {
            return EventSubscription.of("test-base", "test.subscriber.event");
        }
    }

    static final class InheritedSubscriber extends BaseSubscriber<TestEvent> {
        @Override
        public void onEvent(EventEnvelope<TestEvent> envelope) {}
    }

    @SuppressWarnings("rawtypes")
    static final class RawSubscriber implements EventSubscriber {
        @Override
        public EventSubscription subscription() {
            return EventSubscription.of("test-raw", "test.subscriber.event");
        }

        @Override
        public void onEvent(EventEnvelope envelope) {}
    }
}
