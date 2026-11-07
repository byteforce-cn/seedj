package cn.byteforce.seedj.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.domain.event.EventApplier;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 聚合根：raise 绑定状态与事件、replay 只改状态、版本与乐观锁基准。 */
class AbstractAggregateRootTest {

    @Test
    @DisplayName("raise 立即应用事件并登记待提交事件，版本 +1")
    void raiseAppliesAndRegisters() {
        Counter counter = new Counter("c-1");

        counter.increment(2);

        assertThat(counter.total).isEqualTo(2);
        assertThat(counter.version()).isEqualTo(1);
        assertThat(counter.expectedVersion()).isZero();
        assertThat(counter.hasUncommittedEvents()).isTrue();
        assertThat(counter.peekDomainEvents()).hasSize(1);
    }

    @Test
    @DisplayName("peekDomainEvents 返回不可变副本，clearDomainEvents 之后清空")
    void domainEventsAreCopiedAndClearable() {
        Counter counter = new Counter("c-1");
        counter.increment(1);

        List<DomainEvent> peeked = counter.peekDomainEvents();
        assertThatThrownBy(() -> peeked.add(new Incremented(1)))
                .isInstanceOf(UnsupportedOperationException.class);

        counter.clearDomainEvents();

        assertThat(counter.hasUncommittedEvents()).isFalse();
        assertThat(counter.peekDomainEvents()).isEmpty();
        assertThat(counter.version()).isEqualTo(1);
    }

    @Test
    @DisplayName("replay 只应用状态，不登记事件也不改版本")
    void replayOnlyAppliesState() {
        Counter counter = new Counter("c-1");

        counter.replay(new Incremented(5));

        assertThat(counter.total).isEqualTo(5);
        assertThat(counter.version()).isZero();
        assertThat(counter.hasUncommittedEvents()).isFalse();
    }

    @Test
    @DisplayName("replayAll 重放历史并把版本校正到流头版本")
    void replayAllSetsTargetVersion() {
        Counter counter = new Counter("c-1");

        counter.replayAll(List.of(new Incremented(1), new Incremented(2)), 2);

        assertThat(counter.total).isEqualTo(3);
        assertThat(counter.version()).isEqualTo(2);
        assertThat(counter.expectedVersion()).isEqualTo(2);
        assertThat(counter.peekDomainEvents()).isEmpty();
    }

    @Test
    @DisplayName("replayAll 的目标版本不得落后于快照版本")
    void replayAllRejectsBackwardsTarget() {
        Counter counter = new Counter("c-1", 4);

        assertThatThrownBy(() -> counter.replayAll(List.of(), 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("behind snapshot version");
    }

    @Test
    @DisplayName("重建构造器拒绝负版本")
    void rejectsNegativeVersion() {
        assertThatThrownBy(() -> new Counter("c-1", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be >= 0");
    }

    @Test
    @DisplayName("没有对应 apply 方法的事件在 raise 时失败并提示修复方式")
    void missingHandlerFailsOnRaise() {
        Counter counter = new Counter("c-1");

        assertThatThrownBy(() -> counter.replay(new Cleared()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no apply(Cleared) method")
                .hasMessageContaining("implements EventApplier<Cleared>");
    }

    @Test
    @DisplayName("null 事件被拒绝")
    void rejectsNullEvent() {
        Counter counter = new Counter("c-1");

        assertThatThrownBy(() -> counter.replay(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("domain event must not be null");
    }

    @Test
    @DisplayName("apply 抛出的异常被原样解包，而不是反射包装")
    void unwrapsInvocationTargetException() {
        Counter counter = new Counter("c-1");

        assertThatThrownBy(() -> counter.replay(new Incremented(-1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delta must be positive")
                .isNotInstanceOf(java.lang.reflect.InvocationTargetException.class);
    }

    @Test
    @DisplayName("assertAllEventsHandled 在 sealed 事件族未覆盖时失败")
    void assertAllEventsHandledReportsMissing() {
        Order order = new Order("o-1");

        assertThatThrownBy(() -> order.assertAllEventsHandled(OrderEvent.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("does not handle all events")
                .hasMessageContaining("OrderCancelled");
    }

    @Test
    @DisplayName("assertAllEventsHandled 在 sealed 事件族全部覆盖时通过")
    void assertAllEventsHandledPasses() {
        Tally tally = new Tally("t-1");

        tally.assertAllEventsHandled(TallyEvent.class);

        assertThat(
                        cn.byteforce.seedj.domain.event.EventDispatch.missingHandlers(
                                Tally.class, TallyEvent.class))
                .isEmpty();
    }

    // ---------------------------------------------------------------- fixtures

    sealed interface OrderEvent extends DomainEvent permits OrderPlaced, OrderCancelled {}

    static final class OrderPlaced implements OrderEvent {}

    static final class OrderCancelled implements OrderEvent {}

    static final class Order extends AbstractAggregateRoot<String>
            implements EventApplier<OrderEvent> {

        Order(String id) {
            super(id);
        }

        protected void apply(OrderPlaced event) {}
    }

    static final class Incremented implements DomainEvent {

        final int delta;

        Incremented(int delta) {
            this.delta = delta;
        }
    }

    static final class Cleared implements DomainEvent {}

    sealed interface TallyEvent extends DomainEvent permits Started, Stopped {}

    static final class Started implements TallyEvent {}

    static final class Stopped implements TallyEvent {}

    static final class Tally extends AbstractAggregateRoot<String>
            implements EventApplier<TallyEvent> {

        Tally(String id) {
            super(id);
        }

        protected void apply(Started event) {}

        protected void apply(Stopped event) {}
    }

    static final class Counter extends AbstractAggregateRoot<String>
            implements EventApplier<Incremented> {

        private int total;

        Counter(String id) {
            super(id);
        }

        Counter(String id, long version) {
            super(id, version);
        }

        void increment(int delta) {
            raise(new Incremented(delta));
        }

        protected void apply(Incremented event) {
            if (event.delta <= 0) {
                throw new IllegalStateException("delta must be positive");
            }
            total += event.delta;
        }
    }
}
