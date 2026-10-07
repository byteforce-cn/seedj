package cn.byteforce.seedj.starter.coord.it;

import cn.byteforce.coord.sdk.mq.MqClient;
import cn.byteforce.coord.sdk.mq.MqMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消息队列（{@code MqClient}）集成验证。
 *
 * <p>覆盖：建 Topic → 发布（offset 单调）→ poll 消费 → ack 提交偏移（at-least-once
 * 游标语义）→ DLQ 可读。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
class CoordMqIntegrationTest extends AbstractCoordIntegrationTest {

    @Autowired
    private MqClient mqClient;

    @Test
    void publishPollAndAckRoundTrip() {
        String topic = uniqueName("it-mq");
        String group = "it-group";
        mqClient.createTopic(topic, 1);

        byte[] first = "m0".getBytes(StandardCharsets.UTF_8);
        byte[] second = "m1".getBytes(StandardCharsets.UTF_8);
        long offset0 = mqClient.publish(topic, 0, null, first);
        long offset1 = mqClient.publish(topic, 0, "k1".getBytes(StandardCharsets.UTF_8), second);
        assertThat(offset1).isGreaterThan(offset0);

        List<MqMessage> batch = mqClient.poll(topic, 0, group, 0, 10);
        assertThat(batch).extracting(MqMessage::offset).containsExactly(offset0, offset1);
        assertThat(batch.get(0).payload()).isEqualTo(first);
        assertThat(batch.get(1).payload()).isEqualTo(second);

        // ack 提交消费组偏移；从已提交 offset 之后拉取不再重投
        mqClient.ack(topic, group, 0, offset1);
        assertThat(mqClient.poll(topic, 0, group, offset1 + 1, 10)).isEmpty();

        // 无死信时 DLQ 可读且为空（服务可用性冒烟）
        assertThat(mqClient.pollDlq(topic, 0, 10)).isEmpty();
    }

    @Test
    void pollIsCursorBasedOnStartOffset() {
        String topic = uniqueName("it-mq-cursor");
        mqClient.createTopic(topic, 1);

        long offset0 = mqClient.publish(topic, 0, null, "a".getBytes(StandardCharsets.UTF_8));
        long offset1 = mqClient.publish(topic, 0, null, "b".getBytes(StandardCharsets.UTF_8));

        // startOffset 即游标：从 offset1 拉取只见后一条
        List<MqMessage> fromSecond = mqClient.poll(topic, 0, "g", offset1, 10);
        assertThat(fromSecond).extracting(MqMessage::offset).containsExactly(offset1);

        // 从 0 拉取可见全部（未 ack 时不丢历史）
        assertThat(mqClient.poll(topic, 0, "g", 0, 10))
                .extracting(MqMessage::offset)
                .containsExactly(offset0, offset1);
    }
}
