package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.domain.event.EventType;
import cn.byteforce.seedj.eventbus.EventEnvelope;
import cn.byteforce.seedj.eventbus.EventSubscription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 投影基类：订阅 topic 由事件注解推导，消费者组取投影名。
 */
class AbstractProjectionTest {

    @Test
    @DisplayName("订阅 topic 与消费者组来自事件注解和投影名")
    void subscriptionUsesEventTypeAndProjectionName() {
        DirectoryProjection projection = new DirectoryProjection();

        EventSubscription subscription = projection.subscription();

        assertThat(subscription.group()).isEqualTo("hr.employee.directory");
        assertThat(subscription.topics()).containsExactly("hr.employee.onboarded");
        assertThat(subscription.concurrency()).isEqualTo(1);
        assertThat(projection.eventType()).isEqualTo(Onboarded.class);
    }

    @Test
    @DisplayName("信封被解包成事件本体后交给 apply")
    void onEventDelegatesToApply() {
        DirectoryProjection projection = new DirectoryProjection();
        Onboarded event = new Onboarded("E-1");

        projection.onEvent(new EventEnvelope<>(event, "evt-1", "hr.employee.onboarded",
                "hr.employee.directory", 1, Instant.parse("2026-11-06T09:00:00Z"), Map.of()));

        assertThat(projection.applied).containsExactly("E-1");
    }

    @Test
    @DisplayName("投影名为空白时订阅失败")
    void blankProjectionNameFails() {
        AbstractProjection<Onboarded> projection = new AbstractProjection<>(Onboarded.class) {
            @Override
            protected String projectionName() {
                return " ";
            }

            @Override
            protected void apply(Onboarded event) {
            }
        };

        assertThatThrownBy(projection::subscription)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("projectionName() must not be blank");
    }

    @Test
    @DisplayName("事件类型不可为空")
    void eventTypeIsRequired() {
        assertThatThrownBy(() -> new AbstractProjection<Onboarded>(null) {
            @Override
            protected String projectionName() {
                return "p";
            }

            @Override
            protected void apply(Onboarded event) {
            }
        }).isInstanceOf(NullPointerException.class).hasMessageContaining("eventType");
    }

    @EventType("hr.employee.onboarded")
    static final class Onboarded implements DomainEvent {

        private final String employeeNo;

        Onboarded(String employeeNo) {
            this.employeeNo = employeeNo;
        }
    }

    static final class DirectoryProjection extends AbstractProjection<Onboarded> {

        private final List<String> applied = new ArrayList<>();

        DirectoryProjection() {
            super(Onboarded.class);
        }

        @Override
        protected String projectionName() {
            return "hr.employee.directory";
        }

        @Override
        protected void apply(Onboarded event) {
            applied.add(event.employeeNo);
        }
    }
}
