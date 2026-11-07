package cn.byteforce.seedj.starter.eventbus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code seedj.eventbus.*} 配置默认值与 setter 绑定（Spring 绑定只走 setter，默认值必须可预期）。
 */
class EventBusPropertiesTest {

    @Test
    @DisplayName("默认值：启用事件总线与消费者，200ms 轮询、批量 100、租约 30s")
    void defaults() {
        EventBusProperties properties = new EventBusProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getTopics()).isEmpty();

        EventBusProperties.Consumer consumer = properties.getConsumer();
        assertThat(consumer.isEnabled()).isTrue();
        assertThat(consumer.getPollInterval()).isEqualTo(Duration.ofMillis(200));
        assertThat(consumer.getBatchSize()).isEqualTo(100);
        assertThat(consumer.getLease()).isEqualTo(Duration.ofSeconds(30));
        assertThat(consumer.getMaxAttempts()).isEqualTo(5);
        assertThat(consumer.getRetryBase()).isEqualTo(Duration.ofSeconds(1));
        assertThat(consumer.getRetryMax()).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    @DisplayName("消费者配置全部可绑定")
    void consumerSettersBind() {
        EventBusProperties.Consumer consumer = new EventBusProperties().getConsumer();

        consumer.setEnabled(false);
        consumer.setPollInterval(Duration.ofMillis(50));
        consumer.setBatchSize(7);
        consumer.setLease(Duration.ofSeconds(3));
        consumer.setMaxAttempts(9);
        consumer.setRetryBase(Duration.ofMillis(20));
        consumer.setRetryMax(Duration.ofSeconds(4));

        assertThat(consumer.isEnabled()).isFalse();
        assertThat(consumer.getPollInterval()).isEqualTo(Duration.ofMillis(50));
        assertThat(consumer.getBatchSize()).isEqualTo(7);
        assertThat(consumer.getLease()).isEqualTo(Duration.ofSeconds(3));
        assertThat(consumer.getMaxAttempts()).isEqualTo(9);
        assertThat(consumer.getRetryBase()).isEqualTo(Duration.ofMillis(20));
        assertThat(consumer.getRetryMax()).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    @DisplayName("顶层 enabled / topics 可绑定")
    void topLevelSettersBind() {
        EventBusProperties properties = new EventBusProperties();

        properties.setEnabled(false);
        properties.setTopics(List.of("order.paid"));

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getTopics()).containsExactly("order.paid");
    }

    @Test
    @DisplayName("topics 接受 null（等价于空），并复制入参避免外部改动")
    void topicsAreDefensive() {
        EventBusProperties properties = new EventBusProperties();
        List<String> source = new ArrayList<>(List.of("order.paid"));

        properties.setTopics(source);
        source.add("order.cancelled");
        assertThat(properties.getTopics()).containsExactly("order.paid");

        properties.setTopics(null);
        assertThat(properties.getTopics()).isEmpty();
    }
}
