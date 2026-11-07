package cn.byteforce.seedj.eventbus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventSubscriptionTest {

    @Test
    @DisplayName("默认单并发，group 可留空由装配层回退")
    void defaults() {
        EventSubscription subscription = EventSubscription.of(null, "order.cancelled");

        assertThat(subscription.group()).isNull();
        assertThat(subscription.topics()).containsExactly("order.cancelled");
        assertThat(subscription.concurrency()).isEqualTo(1);
    }

    @Test
    @DisplayName("空白 group 归一为 null")
    void blankGroupBecomesNull() {
        assertThat(EventSubscription.of("  ", "t").group()).isNull();
    }

    @Test
    @DisplayName("topic 为空或并发度非法时构造失败")
    void validation() {
        assertThatThrownBy(() -> EventSubscription.of("g"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventSubscription("g", Set.of(" "), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventSubscription("g", Set.of("t"), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
