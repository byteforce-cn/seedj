package cn.byteforce.seedj.domain.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** apply 分派表：精确匹配、父类型回溯、事件族解析与穷尽性校验。 */
class EventDispatchTest {

    @Test
    @DisplayName("分派表只含声明了 apply 的事件类型")
    void tableContainsDeclaredHandlers() {
        Map<Class<?>, Method> table = EventDispatch.table(Order.class);

        assertThat(table.keySet()).containsExactlyInAnyOrder(OrderPlaced.class, OrderPaid.class);
        assertThat(table.get(OrderPlaced.class).getParameterTypes())
                .containsExactly(OrderPlaced.class);
    }

    @Test
    @DisplayName("精确匹配优先于父类型匹配")
    void methodForPrefersExactMatch() {
        Optional<Method> handler = EventDispatch.methodFor(Order.class, OrderPlaced.class);

        assertThat(handler).isPresent();
        assertThat(handler.orElseThrow().getParameterTypes()[0]).isEqualTo(OrderPlaced.class);
    }

    @Test
    @DisplayName("没有精确匹配时回退到最具体的父类型 handler")
    void methodForFallsBackToParentHandler() {
        Optional<Method> alert = EventDispatch.methodFor(Alert.class, AlertRaised.class);
        Optional<Method> critical = EventDispatch.methodFor(Alert.class, CriticalAlertImpl.class);

        assertThat(alert.orElseThrow().getParameterTypes()[0]).isEqualTo(AlertEvent.class);
        assertThat(critical.orElseThrow().getParameterTypes()[0]).isEqualTo(CriticalAlert.class);
    }

    @Test
    @DisplayName("没有匹配的父类型时返回空")
    void methodForReturnsEmptyWhenNoHandler() {
        assertThat(EventDispatch.methodFor(Order.class, Unrelated.class)).isEmpty();
    }

    @Test
    @DisplayName("handledEventTypes 给出已声明事件类型")
    void handledEventTypesListsKeys() {
        Set<Class<?>> handled = EventDispatch.handledEventTypes(Order.class);

        assertThat(handled).containsExactlyInAnyOrder(OrderPlaced.class, OrderPaid.class);
    }

    @Test
    @DisplayName("eventFamily 解析直接实现的事件族")
    void eventFamilyFromDirectImplementation() {
        assertThat(EventDispatch.eventFamily(Order.class)).contains(OrderEvent.class);
        assertThat(EventDispatch.eventFamily(Alert.class)).contains(AlertEvent.class);
    }

    @Test
    @DisplayName("eventFamily 支持从父类继承的具体实现")
    void eventFamilyFromSuperclass() {
        assertThat(EventDispatch.eventFamily(InheritsConcrete.class)).contains(OrderPlaced.class);
    }

    @Test
    @DisplayName("eventFamily 支持经由父接口间接实现")
    void eventFamilyThroughParentInterface() {
        assertThat(EventDispatch.eventFamily(ViaInterface.class)).contains(OrderEvent.class);
    }

    @Test
    @DisplayName("类型变量或裸类型的事件族无法解析，返回空")
    void eventFamilyUnresolvable() {
        assertThat(EventDispatch.eventFamily(GenericBase.class)).isEmpty();
        assertThat(EventDispatch.eventFamily(ConcreteChild.class)).isEmpty();
        assertThat(EventDispatch.eventFamily(RawApplier.class)).isEmpty();
        assertThat(EventDispatch.eventFamily(NotAnApplier.class)).isEmpty();
    }

    @Test
    @DisplayName("applyMethods 收集名字为 apply、单参数、实例非桥接的方法")
    void applyMethodsCollectsCandidates() {
        List<Method> methods = EventDispatch.applyMethods(CandidateScan.class);

        assertThat(methods).extracting(Method::getName).containsOnly("apply");
        assertThat(methods)
                .noneMatch(method -> java.lang.reflect.Modifier.isStatic(method.getModifiers()));
        assertThat(methods)
                .extracting(method -> method.getParameterTypes()[0])
                .containsExactlyInAnyOrder(String.class, OrderPlaced.class);
    }

    @Test
    @DisplayName("抽象 apply 与非 DomainEvent 参数不进分派表")
    void excludesAbstractAndNonDomainEventHandlers() {
        assertThat(EventDispatch.table(AbstractHandler.class).keySet())
                .containsExactly(OrderPaid.class);
        assertThat(EventDispatch.table(TextHandler.class).keySet()).isEmpty();
    }

    @Test
    @DisplayName("sealed 事件族缺少 handler 时列出子类型")
    void missingHandlersListsUncoveredSubtypes() {
        List<Class<?>> missing = EventDispatch.missingHandlers(Order.class, OrderEvent.class);

        assertThat(missing).containsExactly(OrderShipped.class);
    }

    @Test
    @DisplayName("父类型 handler 视为覆盖全部子类型")
    void missingHandlersAcceptsParentHandler() {
        assertThat(EventDispatch.missingHandlers(Alert.class, AlertEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("非 sealed 类型直接失败")
    void missingHandlersRequiresSealedType() {
        assertThatThrownBy(
                        () ->
                                EventDispatch.missingHandlers(
                                        Order.class, OrderEvent.class.getPermittedSubclasses()[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not a sealed interface/class");
    }

    // ---------------------------------------------------------------- fixtures

    sealed interface OrderEvent extends DomainEvent permits OrderPlaced, OrderPaid, OrderShipped {}

    static final class OrderPlaced implements OrderEvent {}

    static final class OrderPaid implements OrderEvent {}

    static final class OrderShipped implements OrderEvent {}

    sealed interface AlertEvent extends DomainEvent
            permits AlertRaised, AlertCleared, CriticalAlert {}

    static final class AlertRaised implements AlertEvent {}

    static final class AlertCleared implements AlertEvent {}

    non-sealed interface CriticalAlert extends AlertEvent {}

    static final class CriticalAlertImpl implements CriticalAlert {}

    static final class Unrelated implements DomainEvent {}

    static final class Order implements EventApplier<OrderEvent> {

        protected void apply(OrderPlaced event) {}

        protected void apply(OrderPaid event) {}
    }

    static final class Alert implements EventApplier<AlertEvent> {

        protected void apply(AlertEvent event) {}

        protected void apply(CriticalAlert event) {}
    }

    interface OrderApplier extends EventApplier<OrderEvent> {}

    static final class ViaInterface implements OrderApplier {

        protected void apply(OrderEvent event) {}
    }

    static final class OrderCreated implements DomainEvent {}

    abstract static class GenericBase<E extends DomainEvent> implements EventApplier<E> {}

    abstract static class ConcreteBase implements EventApplier<OrderPlaced> {}

    static final class InheritsConcrete extends ConcreteBase {

        protected void apply(OrderPlaced event) {}
    }

    static final class ConcreteChild extends GenericBase<OrderCreated> {

        protected void apply(OrderCreated event) {}
    }

    @SuppressWarnings("rawtypes")
    static final class RawApplier implements EventApplier {}

    static final class NotAnApplier {}

    abstract static class AbstractHandler implements EventApplier<OrderEvent> {

        abstract void apply(OrderPlaced event);

        protected void apply(OrderPaid event) {}
    }

    static final class TextHandler {

        void apply(String text) {}
    }

    static final class CandidateScan {

        private void apply(String text) {}

        protected void apply(OrderPlaced event) {}

        static void apply(OrderCreated event) {}

        void apply(OrderCreated first, OrderCreated second) {}

        void handled(OrderCreated event) {}
    }
}
